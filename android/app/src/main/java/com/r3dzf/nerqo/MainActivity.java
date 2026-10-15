package com.r3dzf.nerqo;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.ScrollView;
import android.widget.PopupMenu;
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
    private final int accent = Color.rgb(169,238,210);
    private static final int BG = Color.rgb(20,32,46);
    private static final int SURFACE = Color.rgb(27,43,59);
    private static final int SOFT = Color.rgb(176,198,211);
    private final Handler main = new Handler(Looper.getMainLooper());
    private String incomingText;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
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
        TextView t=new TextView(this);
        t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(sp);
        if(strong)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private GradientDrawable shape(int fill,int radius,int border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);bg.setCornerRadius(dp(radius));
        if(border!=0)bg.setStroke(dp(1),border);
        return bg;
    }
    private LinearLayout.LayoutParams space(int height,int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height < 0? height:dp(height));
        p.topMargin=dp(top);return p;
    }
    private TextView caption(String value) {
        TextView t=text(value,12,true);t.setTextColor(SOFT);t.setLetterSpacing(.12f);return t;
    }
    private Button button(String label,boolean filled) {
        Button b=new Button(this);
        b.setText(label);b.setTextSize(14);b.setAllCaps(false);
        b.setTypeface(null,Typeface.BOLD);
        b.setTextColor(filled ? Color.rgb(24,66,57):Color.WHITE);
        b.setBackground(shape(filled?accent:Color.rgb(43,64,82),14,filled?0:Color.rgb(73,99,116)));
        b.setElevation(0);return b;
    }
    private void initRoot() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);setContentView(root);
    }
    private void showConnect() {
        web=null;initRoot();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24),dp(34),dp(24),dp(32));scroll.addView(body);

        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark=text("↔",27,true);mark.setTextColor(Color.rgb(29,74,63));mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(accent,13,0));brand.addView(mark,new LinearLayout.LayoutParams(dp(46),dp(46)));
        TextView wordmark=text("  Nerqo.",24,true);brand.addView(wordmark);
        body.addView(brand);

        TextView kicker=caption("LOCAL-FIRST SHARING");kicker.setTextColor(accent);
        body.addView(kicker,space(-2,44));
        TextView title=text("Send anything.\nStay connected.",36,true);
        title.setLineSpacing(dp(3),1f);body.addView(title,space(-2,12));
        TextView desc=text("Move files, links and notes between your Android and Windows devices. No cloud. No internet needed.",15,false);
        desc.setTextColor(SOFT);desc.setLineSpacing(dp(5),1f);body.addView(desc,space(-2,14));

        LinearLayout pairCard=new LinearLayout(this);pairCard.setOrientation(LinearLayout.VERTICAL);
        pairCard.setPadding(dp(20),dp(22),dp(20),dp(22));
        pairCard.setBackground(shape(SURFACE,20,Color.rgb(54,79,98)));
        body.addView(pairCard,space(-2,34));
        TextView cardTitle=text("Connect your laptop",20,true);pairCard.addView(cardTitle);
        TextView sub=text("Both devices must be on the same Wi-Fi or hotspot.",13,false);
        sub.setTextColor(SOFT);sub.setPadding(0,dp(6),0,dp(22));pairCard.addView(sub);
        pairCard.addView(caption("LAPTOP ADDRESS"));
        hostInput=new EditText(this);
        hostInput.setSingleLine(true);hostInput.setTextSize(14);
        hostInput.setPadding(dp(16),0,dp(16),0);
        hostInput.setTextColor(Color.WHITE);hostInput.setHintTextColor(Color.rgb(137,160,176));
        hostInput.setHint("192.168.1.7 or 192.168.1.7:44888");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        hostInput.setBackground(shape(Color.rgb(35,53,70),12,Color.rgb(70,96,113)));
        hostInput.setText(getPreferences(MODE_PRIVATE).getString("lastip",""));
        pairCard.addView(hostInput,space(55,10));
        Button connectBtn=button("Connect to laptop   →",true);
        pairCard.addView(connectBtn,space(54,16));
        connectBtn.setOnClickListener(v->connect(hostInput.getText().toString()));
        hostInput.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_GO){connect(hostInput.getText().toString());return true;}return false;
        });
        Button discoverBtn=button("⌁   Find nearby laptop",false);
        pairCard.addView(discoverBtn,space(51,10));
        discoverBtn.setOnClickListener(v->discover(discoverBtn));

        LinearLayout information=new LinearLayout(this);information.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text("◎",25,true);icon.setTextColor(accent);
        information.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(43)));
        TextView info=text("Private local transfers\nPair using the 6-digit code shown on your laptop.",12,false);
        info.setTextColor(SOFT);info.setLineSpacing(dp(3),1f);information.addView(info);
        body.addView(information,space(-2,23));
        Button checkUpdates=button("↻   Check for app updates",false);
        body.addView(checkUpdates,space(50,30));
        checkUpdates.setOnClickListener(v->AppUpdater.check(this));
        TextView footer=text("Nerqo · Android  |  Works without mobile data",11,false);
        footer.setTextColor(Color.rgb(145,171,185));footer.setGravity(Gravity.CENTER);
        body.addView(footer,space(-2,21));
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
        Toolbar bar=new Toolbar(this);bar.setTitle("Nerqo  ↗");bar.setTitleTextColor(Color.WHITE);bar.setSubtitle("LOCAL TRANSFERS");bar.setSubtitleTextColor(SOFT);bar.setBackgroundColor(SURFACE);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));
        bar.getMenu().add(0,1,0,"Change laptop");bar.getMenu().add(0,2,1,"Refresh");bar.getMenu().add(0,3,2,"Check for updates");
        bar.setOnMenuItemClickListener(item->{if(item.getItemId()==1){hostUrl="";getPreferences(MODE_PRIVATE).edit().remove("host").apply();showConnect();return true;}if(item.getItemId()==2){web.reload();return true;}if(item.getItemId()==3){AppUpdater.check(this);return true;}return false;});
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        web.setBackgroundColor(BG);
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
