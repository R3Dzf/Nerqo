package com.r3dzf.nerqo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.net.Network;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;

/** Opt-in native Android updater. Release APK must be signed by the same stable key. */
public final class AppUpdater {
    private static final String REPO = "R3Dzf/Nerqo";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long MAX_BYTES = 3L * 1024 * 1024 * 1024;
    private final Activity activity;
    private final Network internetNetwork;
    private AppUpdater(Activity activity) {
        this.activity = activity;
        // A local-only Wi-Fi/hotspot has no Internet. Avoid rebinding the
        // entire process away from that LAN, which would break transfers.
        // Only the updater's HTTP calls use an independently validated WAN.
        ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network selected = null;
        Network fallback = null;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) { selected = network; break; }
            if (fallback == null) fallback = network;
        }
        internetNetwork = selected != null ? selected : fallback;
    }
    public static void check(Activity activity) {new AppUpdater(activity).runCheck();}
    private void ui(Runnable action){activity.runOnUiThread(action);}
    private void note(String message){ui(()->Toast.makeText(activity,message,Toast.LENGTH_LONG).show());}

    private static String version(Activity activity) throws Exception {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;
    }
    private static boolean newer(String a,String b) {
        if(!a.matches("\\d+\\.\\d+\\.\\d+") || !b.matches("\\d+\\.\\d+\\.\\d+"))return false;
        String[] x=a.split("\\."), y=b.split("\\.");
        for(int i=0;i<3;i++){int delta=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));if(delta!=0)return delta>0;}
        return false;
    }
    private HttpURLConnection request(String path) throws Exception {
        URL url = new URL(path);
        if(!"https".equals(url.getProtocol()))throw new IOException("HTTPS required");
        if (internetNetwork == null) throw new IOException("Internet is not available for app updates. Local file transfers still work.");
        HttpURLConnection conn=(HttpURLConnection)internetNetwork.openConnection(url);
        conn.setConnectTimeout(15000);conn.setReadTimeout(45000);
        conn.setRequestProperty("User-Agent","Nerqo-Updater/0.2");
        conn.setRequestProperty("Accept","application/vnd.github+json");
        conn.connect();
        if(!"https".equals(conn.getURL().getProtocol()) || conn.getResponseCode()!=200) {
            conn.disconnect();throw new IOException("Release server unavailable");
        }
        return conn;
    }
    private byte[] readLimited(String url,int limit) throws Exception {
        HttpURLConnection conn = request(url);
        try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];int count;
            while((count=in.read(b))!=-1) {out.write(b,0,count);if(out.size()>limit)throw new IOException("Metadata too large");}
            return out.toByteArray();
        } finally {conn.disconnect();}
    }
    private static String verifyName(JSONObject item) throws Exception {
        String name=item.getString("name"),sum=item.getString("sha256");
        long size=item.getLong("size");
        if(!Pattern.matches("[A-Za-z0-9._-]{1,90}",name) || !Pattern.matches("[0-9a-f]{64}",sum)
           || size<=0 || size>MAX_BYTES)throw new IOException("Invalid update metadata");
        return name;
    }
    private void runCheck() {
        note("Checking Nerqo updates…");
        new Thread(()->{
            try {
                JSONObject release=new JSONObject(new String(readLimited(API,1024*1024),"UTF-8"));
                String available=release.getString("tag_name");
                if(!available.startsWith("v"))throw new IOException("Unsupported release");
                String ver=available.substring(1);
                if(!newer(ver,version(activity))) {note("You have the latest Nerqo version.");return;}
                String prefix="https://github.com/"+REPO+"/releases/download/v"+ver+"/";
                JSONObject urls=new JSONObject();JSONArray githubAssets=release.getJSONArray("assets");
                for(int i=0;i<githubAssets.length();i++){
                    JSONObject item=githubAssets.getJSONObject(i);
                    urls.put(item.getString("name"),item.getString("browser_download_url"));
                }
                if(!prefix.concat("update-manifest.json").equals(urls.optString("update-manifest.json")))
                    throw new IOException("Release manifest missing");
                JSONObject manifest=new JSONObject(new String(readLimited(prefix+"update-manifest.json",128*1024),"UTF-8"));
                if(manifest.getInt("schema")!=1||!ver.equals(manifest.getString("version")))
                    throw new IOException("Manifest version mismatch");
                JSONObject group=manifest.getJSONObject("assets").getJSONObject("android");
                JSONObject full=group.getJSONObject("full");verifyAsset(full,urls,prefix);
                JSONArray deltas=group.optJSONArray("deltas");
                File base=new File(activity.getApplicationInfo().sourceDir);
                String installedHash=DeltaPatch.sha256(base);
                JSONObject chosen=full;
                if(deltas!=null)for(int i=0;i<deltas.length();i++){
                    JSONObject d=deltas.getJSONObject(i);verifyAsset(d,urls,prefix);
                    if(version(activity).equals(d.optString("from_version")) &&
                        installedHash.equals(d.optString("base_sha256")) &&
                        d.getLong("size") < full.getLong("size")*.9 && d.getLong("size") < chosen.getLong("size"))chosen=d;
                }
                final JSONObject selected=chosen;
                final boolean delta=selected!=full;
                final long size=selected.getLong("size"), fullSize=full.getLong("size");
                ui(()->new AlertDialog.Builder(activity).setTitle("Nerqo "+ver+" available")
                        .setMessage((delta?"Delta update":"Full update")+": "+size/1048576.0+" MB (full: "+fullSize/1048576.0+" MB). Download and install? Internet required for app updates only.")
                        .setNegativeButton("Later",null)
                        .setPositiveButton("Download",(dialog,which)->startInstall(ver,selected,full,urls,base,delta)).show());
            }catch(Exception e){note("Update check failed: "+e.getMessage());}
        },"nerqo-check-update").start();
    }
    private static void verifyAsset(JSONObject meta,JSONObject urls,String prefix)throws Exception {
        String name=verifyName(meta);
        if(!prefix.concat(name).equals(urls.optString(name)))throw new IOException("Untrusted update URL");
    }
    private void download(String url,JSONObject meta,File out) throws Exception {
        long expected=meta.getLong("size"),received=0;
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        HttpURLConnection conn=request(url);
        try(InputStream in=new BufferedInputStream(conn.getInputStream());
            OutputStream file=new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf=new byte[256*1024];int n;
            while((n=in.read(buf))!=-1) {
                received+=n;if(received>expected)throw new IOException("Oversized update");
                file.write(buf,0,n);digest.update(buf,0,n);
            }
        } catch(Exception e) {out.delete();throw e;}
        finally {conn.disconnect();}
        if(received!=expected || !DeltaPatch.hex(digest.digest()).equals(meta.getString("sha256"))) {
            out.delete();throw new IOException("Download SHA-256 mismatch");
        }
    }
    private void startInstall(String version,JSONObject selected,JSONObject full,JSONObject urls,File base,boolean delta) {
        note("Downloading verified Nerqo update…");
        new Thread(()->{
            File folder=new File(activity.getCacheDir(),"nerqo-updates");folder.mkdirs();
            File finished=new File(folder,"Nerqo-update.apk");
            File partial=new File(folder,"download.tmp");
            finished.delete();partial.delete();
            try{
                if(delta){
                    try {
                        download(urls.getString(selected.getString("name")),selected,partial);
                        DeltaPatch.apply(base,partial,finished);
                    }catch(Exception patchFailure){
                        finished.delete();partial.delete();
                        note("Delta unavailable. Downloading full APK…");
                        download(urls.getString(full.getString("name")),full,finished);
                    }
                }else download(urls.getString(full.getString("name")),full,finished);
                partial.delete();
                if(!DeltaPatch.sha256(finished).equals(full.getString("sha256")) || finished.length()!=full.getLong("size"))
                    throw new IOException("APK integrity check failed");
                ui(()->installPrompt());
            }catch(Exception e){finished.delete();note("Update failed: "+e.getMessage());}
        },"nerqo-download-update").start();
    }
    private void installPrompt(){
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("Allow this Nerqo update")
                    .setMessage("Android needs permission to install updates from Nerqo. Allow it, then check updates again. Android verifies the APK signing identity during installation.")
                    .setPositiveButton("Open settings",(d,w)->{
                        Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()));
                        activity.startActivity(i);
                    }).setNegativeButton("Cancel",null).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("APK verified")
            .setMessage("Install the update? Android will verify the existing app's signing certificate. Your data is retained for a compatible upgrade.")
            .setPositiveButton("Install",(d,w)->{
                Uri uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".updates").path("/update.apk").build();
                Intent intent=new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri,"application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try{activity.startActivity(intent);}catch(Exception e){note("Android installer unavailable");}
            }).setNegativeButton("Later",null).show();
    }
}
