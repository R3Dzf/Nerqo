using System.Diagnostics;
using System.IO;
using System.Net.Http.Headers;
using Microsoft.AspNetCore.Http;

namespace Nerqo.Desktop.Services;

public sealed record LiveTransfer(string Id, string Name, string Direction, string Status, long Done, long Size, double Rate)
{
    public double Percent => Size > 0 ? Math.Clamp(Done * 100.0 / Size, 0, 100) : 0;
    public string Detail => $"{Direction} · {Status} · {Bytes(Done)} / {Bytes(Size)}" + (Rate > 0 ? $" · {Bytes((long)Rate)}/s · ~{Math.Ceiling(Math.Max(0, Size - Done) / Rate)}s left" : "");
    private static string Bytes(long n) => n < 1024 ? $"{n} B" : n < 1048576 ? $"{n / 1024.0:0.#} KB" : n < 1073741824 ? $"{n / 1048576.0:0.#} MB" : $"{n / 1073741824.0:0.##} GB";
}

/** Range-aware streaming with measured socket writes and cooperative desktop controls. */
internal sealed class TrackedFileResult(string path, long size, string id, LanHost host) : IResult
{
    public async Task ExecuteAsync(HttpContext context)
    {
        long start = 0, end = size - 1;
        var raw = context.Request.Headers.Range.ToString();
        if (raw.Length > 0)
        {
            if (!RangeHeaderValue.TryParse(raw, out var range) || range.Unit != "bytes" || range.Ranges.Count != 1 || size == 0)
            { context.Response.StatusCode = 416; context.Response.Headers.ContentRange = $"bytes */{size}"; return; }
            var part = range.Ranges.Single();
            start = part.From ?? Math.Max(0, size - (part.To ?? 0));
            end = part.From.HasValue ? Math.Min(part.To ?? end, end) : end;
            if (start >= size || end < start) { context.Response.StatusCode = 416; context.Response.Headers.ContentRange = $"bytes */{size}"; return; }
            context.Response.StatusCode = 206;
            context.Response.Headers.ContentRange = $"bytes {start}-{end}/{size}";
        }
        context.Response.Headers.AcceptRanges = "bytes";
        context.Response.ContentType = "application/octet-stream";
        context.Response.ContentLength = Math.Max(0, end - start + 1);
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true);
        if (stream.Length != size) { context.Response.StatusCode = 409; context.Response.ContentLength = 0; return; }
        stream.Position = start;
        long done = start;
        var clock = Stopwatch.StartNew(); double last = -1;
        try
        {
            host.StreamProgress(id, done, 0, "Sending");
            byte[] buffer = new byte[1024 * 1024];
            while (done <= end)
            {
                if (host.StreamStopped(id)) throw new IOException("Transfer paused or cancelled on Windows");
                int count = await stream.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, end - done + 1)), context.RequestAborted);
                if (count == 0) throw new EndOfStreamException();
                await context.Response.Body.WriteAsync(buffer.AsMemory(0, count), context.RequestAborted);
                done += count;
                if (clock.Elapsed.TotalSeconds - last > .25) { last = clock.Elapsed.TotalSeconds; host.StreamProgress(id, done, (done - start) / Math.Max(.001, last), "Sending"); }
            }
            host.StreamProgress(id, done, 0, "Awaiting verification");
        }
        catch
        {
            host.StreamProgress(id, done, 0, "Interrupted");
            context.Abort();
        }
    }
}
