package com.r3dzf.nerqo;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.text.InputType;
import android.graphics.Typeface;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Native Android companion for the local Python host, with no paid services. */
public final class MainActivity extends Activity {
    private static final int PICK_FILES = 401;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private LinearLayout root;
    private EditText hostInput;
    private String hostUrl;
    private int accent = Color.rgb(161,234,208);
    private final Handler main = new Handler(Looper.getMainLooper());
    private String incomingText;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(12,17,25));
        getWindow().setNavigationBarColor(Color.rgb(12,17,25));
        incomingText = readShare(getIntent());
        hostUrl = getPreferences(MODE_PRIVATE).getString("host", "");
        if(hostUrl.isEmpty()) showConnect(); else showBrowser();
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        String shared=readShare(intent);if(shared!=null){incomingText=shared;if(web!=null)openHome();}
    }
    private String readShare(Intent intent) {
        if(Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            return intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        return null;
    }
    private int dp(float value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int sp,boolean strong){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(sp);if(strong)t.setTypeface(null,Typeface.BOLD);return t;
    }
    private void initRoot() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(12,17,25));setContentView(root);
    }
    private void showConnect() {
        web=null;initRoot();
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(70),dp(24),dp(20));root.addView(body,new LinearLayout.LayoutParams(-1,-1));
        TextView logo=text("↔",52,true);logo.setTextColor(accent);body.addView(logo);
        TextView title=text("Nerqo",32,true);body.addView(title);
        TextView desc=text("Connect to your laptop over Wi-Fi or a hotspot. No internet required.",15,false);desc.setTextColor(Color.rgb(174,193,206));desc.setPadding(0,dp(12),0,dp(24));body.addView(desc);
        hostInput=new EditText(this);hostInput.setSingleLine(true);hostInput.setTextColor(Color.WHITE);hostInput.setHintTextColor(Color.GRAY);
        hostInput.setHint("Laptop IP e.g. 192.168.1.7");hostInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setText(getPreferences(MODE_PRIVATE).getString("lastip",""));
        body.addView(hostInput,new LinearLayout.LayoutParams(-1,dp(60)));
        Button button=new Button(this);button.setText("Connect to laptop");button.setAllCaps(false);body.addView(button);button.setOnClickListener(v->connect(hostInput.getText().toString()));
        Button scan=new Button(this);scan.setText("Find laptop on local Wi-Fi");scan.setAllCaps(false);body.addView(scan);scan.setOnClickListener(v->discover(scan));
        TextView hint=text("The laptop must be running Nerqo. Enter the 6-digit PIN shown on the laptop after connecting.",13,false);
        hint.setTextColor(Color.rgb(148,169,183));hint.setPadding(0,dp(20),0,0);body.addView(hint);
    }
    private void connect(String ip) {
        ip=ip.trim();String target=ip;
        if(target.startsWith("http://"))target=target.substring(7);
        if(target.contains("/"))target=target.substring(0,target.indexOf('/'));
        if(target.matches("[a-zA-Z0-9.-]+"))target=target+":44888";
        // The app deliberately refuses unrelated web URLs.
        if(!target.matches("[a-zA-Z0-9.-]+:[0-9]{1,5}")){
            Toast.makeText(this,"Enter a valid local IP and optional port",Toast.LENGTH_LONG).show();return;
        }
        String host=target.substring(0,target.lastIndexOf(':'));
        if(!(host.equals("localhost")||host.matches("10\\.\\d+\\.\\d+\\.\\d+")||
                host.matches("192\\.168\\.\\d+\\.\\d+")||host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\.\\d+\\.\\d+")||
                host.matches("169\\.254\\.\\d+\\.\\d+"))){
            Toast.makeText(this,"Use a local/private IP address",Toast.LENGTH_LONG).show();return;
        }
        hostUrl="http://"+target;
        getPreferences(MODE_PRIVATE).edit().putString("host",hostUrl).putString("lastip",host).apply();showBrowser();
    }
    private void discover(Button button){
        button.setEnabled(false);button.setText("Searching…");
        new Thread(()->{
            String ip=null;
            try(DatagramSocket sock=new DatagramSocket()){
                sock.setBroadcast(true);sock.setSoTimeout(2200);
                byte[] payload="NERQO_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(payload,payload.length,InetAddress.getByName("255.255.255.255"),44889));
                byte[] data=new byte[512];DatagramPacket packet=new DatagramPacket(data,data.length);
                sock.receive(packet);
                JSONObject result=new JSONObject(new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8));
                if("Nerqo".equals(result.optString("app")))ip=packet.getAddress().getHostAddress()+":"+result.optInt("port",44888);
            }catch(Exception ignored){}
            String found=ip;
            main.post(()->{button.setEnabled(true);button.setText("Find laptop on local Wi-Fi");if(found!=null){hostInput.setText(found);connect(found);}else Toast.makeText(this,"Not found. Enter the IP shown on the laptop.",Toast.LENGTH_LONG).show();});
        }).start();
    }
    private void showBrowser() {
        initRoot();
        Toolbar bar=new Toolbar(this);bar.setTitle("Nerqo");bar.setTitleTextColor(Color.WHITE);bar.setBackgroundColor(Color.rgb(16,23,34));
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(48)));
        bar.getMenu().add(0,1,0,"Change laptop");bar.getMenu().add(0,2,1,"Refresh");
        bar.setOnMenuItemClickListener(item->{if(item.getItemId()==1){hostUrl="";getPreferences(MODE_PRIVATE).edit().remove("host").apply();showConnect();return true;}if(item.getItemId()==2){web.reload();return true;}return false;});
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        web.setBackgroundColor(Color.rgb(12,17,25));
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString()+" NerqoAndroid");
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(true);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){
                Uri url=req.getUrl();
                if(!url.toString().startsWith(hostUrl+"/")){
                    if(!"http".equals(url.getScheme()) && !"https".equals(url.getScheme()))return true;
                    Intent open=new Intent(Intent.ACTION_VIEW,url);try{startActivity(open);}catch(Exception e){}
                    return true;
                }
                return false;
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError err){
                if(req.isForMainFrame())Toast.makeText(MainActivity.this,"Cannot reach laptop. Check Wi-Fi or change the address.",Toast.LENGTH_LONG).show();
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(fileCallback!=null)fileCallback.onReceiveValue(null);
                fileCallback=callback;
                try{Intent pick=params.createIntent();pick.addCategory(Intent.CATEGORY_OPENABLE);pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(pick,PICK_FILES);return true;}
                catch(Exception e){fileCallback=null;return false;}
            }
        });
        web.setDownloadListener((url,userAgent,contentDisposition,mimeType,contentLength)->{
            try{
                if(!url.startsWith(hostUrl+"/api/files/"))throw new IllegalArgumentException("Untrusted download URL");
                DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
                String cookie=CookieManager.getInstance().getCookie(url);
                if(cookie!=null)req.addRequestHeader("Cookie",cookie);
                req.setTitle("Nerqo transfer");req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,android.webkit.URLUtil.guessFileName(url,contentDisposition,mimeType));
                ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this,"Downloading to Downloads",Toast.LENGTH_SHORT).show();
            }catch(Exception e){Toast.makeText(this,"Download failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        });
        openHome();
    }
    private void openHome(){if(web==null)return;String url=hostUrl+"/";
        if(incomingText!=null && !incomingText.isEmpty()){url+="?share="+Uri.encode(incomingText);incomingText=null;}
        web.loadUrl(url);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req!=PICK_FILES)return;
        if(fileCallback==null)return;
        Uri[] resultUris=null;
        if(result==RESULT_OK && data!=null){
            if(data.getClipData()!=null){int n=data.getClipData().getItemCount();resultUris=new Uri[n];for(int i=0;i<n;i++)resultUris[i]=data.getClipData().getItemAt(i).getUri();}
            else if(data.getData()!=null)resultUris=new Uri[]{data.getData()};
        }
        fileCallback.onReceiveValue(resultUris);fileCallback=null;
    }
    @Override public void onBackPressed(){if(web!=null && web.canGoBack())web.goBack();else if(web!=null)showConnect();else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}
