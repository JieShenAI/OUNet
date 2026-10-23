package cn.jieshen.oucautologin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BLUE=0xff004098, INK=0xff17334d, MUTED=0xff536d83, GREEN=0xff087b63, AMBER=0xff8c5900;
    private AppStore store;
    private LinearLayout body,fieldGroup,timeline;
    private TextView status,detail,infoHint,accountHint,monitorHint,recoveryCount,recent,permissionText,recordsHeading;
    private TextView[] infoValues;
    private TextView wifiName,meterText,updateText;
    private android.widget.ImageView prohibited;
    private View deviceLink;
    private LinearLayout credentialPanel;
    private TextView credentialTitle;
    private Button credentialEdit;
    private DeviceRepository.Value renderedDevices;
    private AlertDialog updateDialog,logsDialog;
    private TextView logsText,logsHint;
    private ScrollView logsScroll;
    private long logsRevision=-1;
    private Button connectButton;
    private Button[] nav;
    private Spinner accounts;
    private Switch monitor;
    private EditText interval;
    private List<String> accountIds=new ArrayList<>();
    private boolean updating,visible;
    private int tab,recordOffset;
    private String intervalDraft="";
    private long renderedGeneration=Long.MIN_VALUE;
    private long recordsRevision=-1;
    private WifiAccess.Observer observer;
    private AlertDialog settingsDialog;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshed=()->{if(visible&&!isDestroyed())refresh();};
    private final Runnable networkEvent=()->{
        if(!visible)return;
        CheckCoordinator.request(this,store.enabled()?RunGate.AUTOMATIC:RunGate.MANUAL,"网络变化",refreshed);
        refresh();
    };
    private final Runnable ticker=new Runnable(){public void run(){if(visible){refresh();handler.postDelayed(this,store.intervalMs());}}};

    private final Runnable logsTicker=new Runnable(){public void run(){if(visible&&logsDialog!=null&&logsDialog.isShowing()){refreshLogs();handler.postDelayed(this,1000);}}};

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,boolean bold){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(INK);v.setPadding(0,dp(4),0,dp(4));
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private TextView secondary(String value){TextView t=text(value,14,false);t.setTextColor(MUTED);return t;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private android.graphics.drawable.RippleDrawable ripple(int color,int radius){return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x24004098),surface(color,radius),surface(Color.WHITE,radius));}
    private LinearLayout card(LinearLayout parent){
        LinearLayout v=column();v.setPadding(dp(16),dp(12),dp(16),dp(12));GradientDrawable bg=surface(Color.WHITE,20);bg.setStroke(dp(1),0xffdce6ed);v.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);parent.addView(v,p);return v;
    }
    private EditText field(LinearLayout parent,String label,String value,int type,String hint){
        parent.addView(secondary(label));EditText edit=new EditText(this);edit.setTextSize(16);edit.setInputType(type);edit.setSingleLine(true);edit.setText(value);edit.setHint(hint);
        edit.setMinHeight(dp(48));edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);parent.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;
    }
    private Button button(LinearLayout parent,String label,boolean primary,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setMinHeight(dp(48));b.setTextColor(primary?Color.WHITE:BLUE);
        b.setBackground(ripple(primary?BLUE:0xffeaf3fa,12));b.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }
    private ScrollView scroll(View content){ScrollView s=new ScrollView(this);s.addView(content);return s;}
    private LinearLayout dialogContent(){LinearLayout c=column();c.setPadding(dp(20),dp(8),dp(20),dp(16));return c;}
    private Spinner picker(LinearLayout parent,List<String> labels){
        Spinner spinner=new Spinner(this);setOptions(spinner,labels);spinner.setMinimumHeight(dp(48));parent.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;
    }
    private void setOptions(Spinner spinner,List<String> labels){
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            private View wrap(View view){if(view instanceof TextView){TextView t=(TextView)view;t.setSingleLine(false);t.setEllipsize(null);t.setTextSize(16);t.setMinHeight(dp(48));t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(8),dp(8),dp(8),dp(8));}return view;}
            @Override public View getView(int position,View view,android.view.ViewGroup parent){return wrap(super.getView(position,view,parent));}
            @Override public View getDropDownView(int position,View view,android.view.ViewGroup parent){return wrap(super.getDropDownView(position,view,parent));}
        };a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(a);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new AppStore(this);
        if(state!=null){tab=state.getInt("tab",0);intervalDraft=state.getString("interval_draft","");}
        LinearLayout root=column();root.setBackgroundColor(0xfff5f8fb);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});else root.setFitsSystemWindows(true);
        LinearLayout header=column();header.setPadding(dp(16),dp(8),dp(16),0);root.addView(header);
        wifiName=text("正在读取Wi-Fi",16,true);wifiName.setMinHeight(dp(48));wifiName.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);wifiName.setPadding(dp(16),dp(8),dp(16),dp(8));wifiName.setBackground(surface(0xffedf4f9,14));header.addView(wifiName,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(0,dp(10),0,dp(6));header.addView(brand);
        android.widget.ImageView brandIcon=new android.widget.ImageView(this);brandIcon.setImageResource(R.mipmap.ic_launcher);brandIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams brandIconParams=new LinearLayout.LayoutParams(dp(42),dp(42));brandIconParams.setMarginEnd(dp(10));brand.addView(brandIcon,brandIconParams);LinearLayout names=column();brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));TextView title=text("OUNet",20,true);title.setTextColor(BLUE);names.addView(title);names.addView(secondary("校园网络辅助工具 · 独立开发"));
        body=column();body.setPadding(dp(16),0,dp(16),dp(16));ScrollView page=scroll(body);root.addView(page,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout navigation=new LinearLayout(this);navigation.setPadding(dp(8),0,dp(8),0);navigation.setBackgroundColor(Color.WHITE);root.addView(navigation);nav=new Button[3];
        String[] labels={"连接概览","恢复记录","设置"};for(int i=0;i<3;i++){final int pageId=i;Button n=new Button(this);n.setText(labels[i]);n.setTextSize(14);n.setAllCaps(false);n.setMinHeight(dp(48));n.setBackgroundColor(Color.TRANSPARENT);navigation.addView(n,new LinearLayout.LayoutParams(0,-2,1));n.setOnClickListener(v->navigate(pageId));nav[i]=n;}
        TextView notice=text(getString(R.string.free_notice),12,false);notice.setGravity(Gravity.CENTER);notice.setTextColor(MUTED);notice.setPadding(dp(12),dp(4),dp(12),dp(6));root.addView(notice);
        setContentView(root);navigate(tab);
    }
    private void navigate(int selected){
        if(interval!=null)intervalDraft=interval.getText().toString();tab=selected;body.removeAllViews();
        credentialPanel=null;credentialTitle=null;credentialEdit=null;connectButton=null;status=null;detail=null;fieldGroup=null;infoValues=null;accounts=null;monitor=null;interval=null;recoveryCount=null;recent=null;recordsHeading=null;timeline=null;meterText=null;prohibited=null;deviceLink=null;renderedDevices=null;renderedGeneration=Long.MIN_VALUE;
        for(int i=0;i<nav.length;i++){nav[i].setTextColor(i==(tab==3?0:tab)?BLUE:MUTED);nav[i].setTypeface(Typeface.DEFAULT,i==(tab==3?0:tab)?Typeface.BOLD:Typeface.NORMAL);nav[i].setSelected(i==(tab==3?0:tab));nav[i].setBackground(ripple(i==(tab==3?0:tab)?0xffeaf3fa:Color.TRANSPARENT,12));}
        if(tab==0)buildOverview();else if(tab==1)buildRecords();else if(tab==2)buildPreferences();else buildDevices();refresh();
    }
    private void buildOverview(){
        LinearLayout current=card(body);LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);current.addView(heading);heading.addView(text("连接概览",18,true),new LinearLayout.LayoutParams(0,-2,1));status=text("正在检测",24,true);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);heading.addView(status);
        prohibited=new android.widget.ImageView(this);prohibited.setImageResource(R.drawable.ic_prohibited);prohibited.setContentDescription("非校园网");LinearLayout.LayoutParams icon=new LinearLayout.LayoutParams(dp(72),dp(72));icon.gravity=Gravity.CENTER_HORIZONTAL;icon.topMargin=dp(12);current.addView(prohibited,icon);prohibited.setVisibility(View.GONE);
        detail=secondary("");current.addView(detail);
        credentialPanel=column();credentialPanel.setPadding(dp(14),dp(10),dp(14),dp(12));credentialPanel.setBackground(surface(0xfffff1f0,14));current.addView(credentialPanel);
        credentialTitle=text("",20,true);credentialTitle.setTextColor(0xffb42318);credentialTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);credentialPanel.addView(credentialTitle);
        credentialPanel.addView(secondary("请检查并修改当前账号密码，再重新检测并连接。自动重试已暂停。"));
        credentialEdit=button(credentialPanel,"修改账号或密码",false,()->{try{SettingsData c=store.config();if(store.authError(c)==AuthError.PASSWORD&&c.active()!=null)showAccountEditor(c.active());else showAccounts();}catch(Exception e){showError(e);}});
        credentialPanel.setVisibility(View.GONE);fieldGroup=column();current.addView(fieldGroup);infoValues=new TextView[6];
        String[] labels={"账号","上线时间","源IP","源IPv6","源MAC","移动端标识"};
        for(int i=0;i<labels.length;i++){
            LinearLayout row=new LinearLayout(this);boolean stacked=getResources().getConfiguration().fontScale>1.3f;
            row.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);row.setPadding(0,dp(4),0,dp(4));
            TextView label=secondary(labels[i]);row.addView(label,stacked?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(dp(88),-2));
            TextView value=text("读取中…",16,false);value.setTextIsSelectable(true);row.addView(value,stacked?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));fieldGroup.addView(row);infoValues[i]=value;
        }
        LinearLayout information=new LinearLayout(this);information.setGravity(Gravity.CENTER_VERTICAL);fieldGroup.addView(information);infoHint=secondary("");information.addView(infoHint,new LinearLayout.LayoutParams(0,-2,1));Button refresh=new Button(this);refresh.setText("刷新");refresh.setTextColor(BLUE);refresh.setTextSize(14);refresh.setMinWidth(0);refresh.setMinHeight(dp(48));refresh.setBackground(ripple(0xffeaf3fa,12));refresh.setContentDescription("刷新会话信息");information.addView(refresh,new LinearLayout.LayoutParams(dp(64),-2));refresh.setOnClickListener(v->refreshSession());
        deviceLink=button(fieldGroup,"查看其他在线设备 →",false,()->{navigate(3);requestDevices(false);});
        LinearLayout account=card(body);LinearLayout accountHeading=new LinearLayout(this);accountHeading.setGravity(Gravity.CENTER_VERTICAL);account.addView(accountHeading);accountHeading.addView(text("认证使用账号",18,true),new LinearLayout.LayoutParams(0,-2,1));Button manage=new Button(this);manage.setText("管理");manage.setTextColor(BLUE);manage.setTextSize(14);manage.setMinWidth(0);manage.setMinHeight(dp(48));manage.setBackground(ripple(0xffeaf3fa,12));accountHeading.addView(manage,new LinearLayout.LayoutParams(dp(64),-2));manage.setOnClickListener(v->showAccounts());accounts=picker(account,new ArrayList<>());accountHint=secondary("");account.addView(accountHint);
        accounts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){
            if(updating||position>=accountIds.size()||accountIds.get(position).isEmpty())return;
            try{String selected=accountIds.get(position);if(selected.equals(storeId()))return;store.selectAccount(selected);configurationChanged(true,"切换账号");}catch(Exception e){showError(e);}
        }});
        LinearLayout automatic=card(body);monitor=new Switch(this);monitor.setText("自动监测");monitor.setTextSize(18);monitor.setMinHeight(dp(48));automatic.addView(monitor);monitorHint=secondary("");automatic.addView(monitorHint);monitor.setOnCheckedChangeListener((v,checked)->{if(!updating)toggleMonitor(checked);});
        LinearLayout summary=card(body);recoveryCount=text("",18,true);recoveryCount.setTextColor(BLUE);summary.addView(recoveryCount);recent=secondary("");summary.addView(recent);button(summary,"查看全部恢复记录",false,()->navigate(1));
        connectButton=button(body,"重新检测并连接",true,this::connect);
    }
    private void buildPreferences(){
        LinearLayout automatic=card(body);automatic.addView(text("检查计划",18,true));
        interval=field(automatic,"状态评估间隔（秒，最低0.3；推荐1）",intervalDraft.isEmpty()?store.intervalText():intervalDraft,InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL,"1");
        LinearLayout presets=new LinearLayout(this);automatic.addView(presets);for(String value:new String[]{"0.3","1","3","10"}){Button preset=new Button(this);preset.setText(getString(R.string.seconds_text,value));presets.addView(preset,new LinearLayout.LayoutParams(0,-2,1));preset.setOnClickListener(v->interval.setText(value));}
        button(automatic,"保存间隔",false,()->{try{store.setIntervalMs(SettingsData.parseSeconds(interval.getText().toString()));configurationChanged(false,"保存间隔");toast("已保存，本地评估与HTTP请求频率分别控制");}catch(Exception e){interval.setError(e.getMessage());}});
        automatic.addView(secondary("默认1秒，最小0.3秒。断网时按设定节拍轮换探测，不设每分钟请求配额或失败退避；稳定在线约15秒探测一次。请求耗时和锁屏调度可能延后。"));
        LinearLayout tools=card(body);tools.addView(text("账号与系统",18,true));button(tools,"管理校园网账号",false,this::showAccounts);button(tools,"权限与品牌后台设置",false,this::showSettings);button(tools,"诊断记录",false,this::showLogs);
        button(tools,"重置失败保护",false,()->new AlertDialog.Builder(this).setTitle("允许重新尝试认证？").setMessage("清除明确认证失败记录。已取消固定认证冷却；点击首页重新检测并连接可同时重置并立即尝试。")
            .setPositiveButton("重置",(d,w)->{try{store.resetRetry();readOnly();}catch(Exception e){showError(e);}}).setNegativeButton("取消",null).show());
        LinearLayout docs=card(body);docs.addView(text("使用文档",18,true));button(docs,"APP介绍",false,()->showDocument("APP介绍",R.raw.app_intro));button(docs,"数据使用说明",false,()->showDocument("数据使用说明",R.raw.data_usage));button(docs,"版权与第三方声明",false,()->showDocument("版权与第三方声明",R.raw.copyright_notice));button(docs,"检查更新",false,this::showUpdate);
        TextView about=secondary("OUNet "+BuildConfig.VERSION_NAME+"\nOUNet 海浪连接标识 · 独立工具，非学校官方客户端\n密码通过本机Keystore加密保存。\n"+getString(R.string.free_notice));body.addView(about);
    }
    private void buildRecords(){
        recordsRevision=RecoveryRepository.get(this).revision();
        recordOffset=0;LinearLayout summary=card(body);recordsHeading=text("",20,true);summary.addView(recordsHeading);summary.addView(secondary("本次运行开始于 "+time(RecoveryRepository.get(this).startedAt)));summary.addView(secondary("只统计认证后稳定复通；自行恢复另列。"));
        timeline=column();body.addView(timeline);loadRecords();LinearLayout calls=card(body);calls.addView(text("校园网调用（XHA）",18,true));meterText=secondary("");calls.addView(meterText);calls.addView(secondary("包含失败的应用层请求尝试；进程结束后重新统计。"));
    }
    private void loadRecords(){
        RecoveryRepository repo=RecoveryRepository.get(this);List<RecoveryRepository.Event> events=repo.page(recordOffset,30);
        if(recordOffset==0&&events.isEmpty())timeline.addView(secondary(repo.error().isEmpty()?"本次启动暂无恢复记录":repo.error()));
        for(RecoveryRepository.Event e:events){LinearLayout row=card(timeline);boolean assisted=e.kind.equals("ASSISTED");TextView heading=text(time(e.recoveredAt)+" · "+(assisted?"已协助恢复":"网络自行恢复"),16,true);heading.setTextColor(assisted?GREEN:MUTED);row.addView(heading);row.addView(secondary(e.ssid+" · 耗时"+(e.durationMs/1000)+"秒"));
            TextView more=secondary("查看详情 ▾");more.setMinHeight(dp(48));row.addView(more);TextView details=secondary("发现故障："+time(e.detectedAt)+"\n触发："+e.reason+"\n认证账号："+e.account+"\n尝试轮数："+e.attempts+(assisted?"":"\n未确认由本APP认证恢复，不计入协助恢复次数"));details.setVisibility(View.GONE);row.addView(details);more.setOnClickListener(v->{boolean expanded=details.getVisibility()==View.VISIBLE;details.setVisibility(expanded?View.GONE:View.VISIBLE);more.setText(expanded?"查看详情 ▾":"收起详情 ▴");});}
        recordOffset+=events.size();
        if(events.size()==30){Button next=button(timeline,"加载更多",false,()->{});next.setOnClickListener(v->{timeline.removeView(next);loadRecords();});}
    }
    private void toggleMonitor(boolean checked){
        try{
            if(checked){if(!store.configured()){toast("请先添加并选用认证账号");refresh();return;}store.setEnabled(true);CheckCoordinator.cancel();MonitorService.start(this);}
            else{CheckCoordinator.cancel();stopService(new Intent(this,MonitorService.class));store.setEnabled(false);readOnly();}
        }catch(Exception e){try{store.setEnabled(false);}catch(Exception ignored){}toast("未能更新监测，请检查系统权限与存储");}refresh();
    }
    private void readOnly(){CheckCoordinator.request(this,RunGate.READ_ONLY,refreshed);}
    private void connect(){
        try{CheckCoordinator.reconnect(this,refreshed);refresh();}catch(Exception e){showError(e);}
    }
    private void configurationChanged(boolean authenticate,String reason){
        handler.removeCallbacks(networkEvent);CheckCoordinator.cancel();ConnectionStatus.invalidate("配置已更新，正在检测");renderedGeneration=Long.MIN_VALUE;
        if(store.enabled())MonitorService.reschedule(this);else stopService(new Intent(this,MonitorService.class));
        CheckCoordinator.request(this,authenticate?RunGate.MANUAL:RunGate.READ_ONLY,reason,refreshed);refresh();
    }
    private void refreshSession(){
        try{SettingsData c=store.config();WifiAccess.Snapshot s=WifiAccess.snapshot(this,true);WifiAccess.Link link=WifiAccess.actual(this,true);ConnectionStatus.Value state=ConnectionStatus.get();
            if(link==null||!ConnectionStatus.authenticated(state.state)||!WifiAccess.key(link.network).equals(state.key)){toast("请先确认网络已连通");return;}SessionInfoRepository.request(this,c,link.network,state.key,true);refresh();
        }catch(Exception e){showError(e);}
    }
    private void refresh(){
        WifiAccess.Snapshot snapshot=WifiAccess.snapshot(this,true);
        WifiAccess.Link actual=WifiAccess.actual(this,true);wifiName.setText(WifiAccess.title(snapshot,actual));wifiName.setContentDescription("Wi-Fi名称，"+wifiName.getText());
        if(connectButton!=null){boolean busy=CheckCoordinator.reconnecting();connectButton.setEnabled(!busy);connectButton.setText(busy?"正在重新连接…":"重新检测并连接");}
        if(meterText!=null)meterText.setText(getString(R.string.xha_counts,RequestControl.GLOBAL.total(),RequestControl.GLOBAL.minute()));
        if(updateDialog!=null&&updateDialog.isShowing()&&updateText!=null)updateText.setText(updateMessage());
        if(tab==3){renderDevices();return;}
        try{
            SettingsData c=store.config();ConnectionStatus.Value connection=ConnectionStatus.get();AuthError authError=store.authError(c);
            WifiAccess.Link link=actual;
            boolean current=!snapshot.networkPermissionMissing&&connection.generation==c.generation&&link!=null&&WifiAccess.key(link.network).equals(connection.key);
            boolean online=current&&connection.state==ConnectionStatus.State.ONLINE;
            if(credentialPanel!=null){credentialPanel.setVisibility(authError==AuthError.NONE||online?View.GONE:View.VISIBLE);credentialTitle.setText(authError.title());credentialEdit.setText(authError==AuthError.PASSWORD?"修改密码":"检查账号和密码");}
            boolean authenticated=current&&ConnectionStatus.authenticated(connection.state);
            SessionInfoRepository.Value session=SessionInfoRepository.get();
            boolean same=session.key.equals(connection.key)&&session.generation==c.generation;
            boolean history=current&&!authenticated&&connection.state==ConnectionStatus.State.INDETERMINATE&&same&&session.info!=null;
            if(status!=null){
                String label=!store.healthy()?"暂无法确认":snapshot.networkPermissionMissing?"权限不足":!current?(snapshot.links.isEmpty()?"非校园网":"正在识别"):online?"已联网":authenticated?"已登录":connection.state==ConnectionStatus.State.NON_CAMPUS?"非校园网":connection.state==ConnectionStatus.State.CREDENTIAL_ERROR?"认证失败":connection.state==ConnectionStatus.State.OFFLINE?"校园网":connection.state==ConnectionStatus.State.INDETERMINATE?"正在确认":"正在识别";
                status.setText(label);status.setTextColor(online?GREEN:(label.equals("非校园网")||label.equals("认证失败"))?0xffb42318:label.equals("校园网")?AMBER:INK);
                prohibited.setVisibility(label.equals("非校园网")?View.VISIBLE:View.GONE);
                String explanation=snapshot.networkPermissionMissing?"没有网络或位置权限，请检查应用权限":current?connection.detail:snapshot.links.isEmpty()?"尚未连接Wi-Fi":connection.detail;
                if(explanation.contains("等待下一次网络复检"))explanation="正在复检网络，请稍候";
                detail.setText(!store.healthy()?"本地写入失败，请检查存储并重启":online?"外网验证通过":explanation);detail.setVisibility(View.VISIBLE);
                if(current&&connection.state==ConnectionStatus.State.CREDENTIAL_ERROR&&authError!=AuthError.NONE)detail.setVisibility(View.GONE);
                fieldGroup.setVisibility(authenticated||history?View.VISIBLE:View.GONE);deviceLink.setVisibility(authenticated?View.VISIBLE:View.GONE);
                if(authenticated||history){SessionInfoClient.Info info=same?session.info:null;
                    String[] values=info==null?null:info.fields();for(int i=0;i<6;i++)infoValues[i].setText(values==null?(same&&session.loading?"读取中…":online?"尚未读取":"页面未提供"):values[i]);
                    infoHint.setText(info!=null?(history?"上次读取，当前状态未确认 · ":session.loading?"上次读取，正在刷新 · ":session.message.isEmpty()?"XHA信息读取于 · ":session.message+" · ")+time(info.fetchedAt):same&&session.loading?"正在读取学校会话":online?"外网可用，未查询XHA；可点击刷新读取账号信息":"会话信息暂不可用，可单独重试");
                }
                if(c.generation!=renderedGeneration){updating=true;accountIds=new ArrayList<>();List<String> labels=new ArrayList<>();accountIds.add("");labels.add(c.accounts.isEmpty()?"添加校园网账号":"请选择账号");int selection=0;for(SettingsData.Account a:c.accounts){accountIds.add(a.id);labels.add(a.display());if(a.id.equals(c.activeId))selection=labels.size()-1;}setOptions(accounts,labels);accounts.setSelection(selection);monitor.setChecked(c.enabled);updating=false;renderedGeneration=c.generation;}
                accountHint.setText(c.active()==null?"未选账号；仍可检测网络及读取在线信息":"切换后立即检测，断网时按需认证；在线账号以上方学校会话为准");
                RetryPolicy retry=store.retry();monitorHint.setText(getString(R.string.monitor_summary,c.intervalText(),!online&&authError!=AuthError.NONE?authError.title()+"，已暂停重试":!online&&retry.blocked?"认证已暂停":MonitorService.running?"监测中":c.enabled?"服务未运行，请检查后台设置":"监测关闭"));
                RecoveryRepository repo=RecoveryRepository.get(this);synchronized(repo){recoveryCount.setText(getString(R.string.recovery_count,repo.count()));List<RecoveryRepository.Event> latest=repo.page(0,1);recent.setText(!repo.error().isEmpty()?repo.error():latest.isEmpty()?"暂无恢复记录":time(latest.get(0).recoveredAt)+" · "+(latest.get(0).kind.equals("ASSISTED")?"认证后恢复":"网络自行恢复（不计数）"));}
            }
            if(recordsHeading!=null){RecoveryRepository repo=RecoveryRepository.get(this);synchronized(repo){if(recordsRevision!=repo.revision()){timeline.removeAllViews();recordOffset=0;loadRecords();recordsRevision=repo.revision();}recordsHeading.setText(getString(R.string.recovery_count,repo.count()));}}
        }catch(Exception e){updating=false;if(detail!=null){detail.setText(e instanceof IOException?e.getMessage():"请检查本地配置和系统权限");detail.setVisibility(View.VISIBLE);}}
        if(settingsDialog!=null&&settingsDialog.isShowing()&&permissionText!=null)permissionText.setText(DeviceSettingsGuide.permissions(this));
    }
    private String time(long value){return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(value));}
    private void showAccounts(){
        try{
            SettingsData c=store.config();LinearLayout list=dialogContent();
            list.addView(text("切换后立即检测网络，断网时按需认证。密码不会回填。",13,false));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("管理校园网账号").setView(scroll(list)).setNegativeButton("关闭",null).create();
            for(SettingsData.Account a:c.accounts){
                list.addView(text(a.display()+(a.id.equals(c.activeId)?"（当前）":""),16,true));
                button(list,"选用此账号",false,()->{try{if(a.id.equals(storeId())){dialog.dismiss();return;}store.selectAccount(a.id);dialog.dismiss();configurationChanged(true,"切换账号");}catch(Exception e){showError(e);}});
                button(list,"编辑备注与密码",false,()->{dialog.dismiss();showAccountEditor(a);});
                button(list,"删除此账号",false,()->new AlertDialog.Builder(this).setTitle("删除 "+a.display()+"？")
                    .setMessage("删除当前账号会关闭自动监测并要求重新选择；失败保护保留。")
                    .setPositiveButton("删除",(d,w)->{try{store.deleteAccount(a.id);dialog.dismiss();configurationChanged(false,"保存账号");showAccounts();}catch(Exception e){showError(e);}}).setNegativeButton("取消",null).show());
            }
            button(list,"新增校园网账号",true,()->{dialog.dismiss();showAccountEditor(null);});dialog.show();
        }catch(Exception e){showError(e);}
    }
    private void showAccountEditor(SettingsData.Account account){
        LinearLayout form=dialogContent();
        EditText label=field(form,"备注（可选，最多40字符）",account==null?"":account.label,InputType.TYPE_CLASS_TEXT,"我的账号");
        EditText username=field(form,"校园网账号",account==null?"":account.username,InputType.TYPE_CLASS_TEXT,"输入校园网账号");username.setEnabled(account==null);
        EditText password=field(form,"校园网密码","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,account==null?"首次密码必填":"留空只保留此账号密码");password.setSaveEnabled(false);
        CheckBox select=new CheckBox(this);select.setText("保存后选用；切换账号时检测并按需连接");select.setChecked(account==null||account.id.equals(storeId()));form.addView(select);
        TextView error=text("",13,false);error.setTextColor(Color.rgb(170,55,45));error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(account==null?"新增账号":"编辑账号（账号名只读）").setView(scroll(form)).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                String before=storeId();String id=store.saveAccount(account==null?null:account.id,label.getText().toString(),username.getText().toString(),password.getText().toString());
                password.setText("");if(select.isChecked()&&!id.equals(before))store.selectAccount(id);dialog.dismiss();configurationChanged(select.isChecked()&&!id.equals(before),"切换账号");toast("账号已保存，密码已加密");
            }catch(IllegalArgumentException e){error.setText(e.getMessage());}
            catch(Exception e){error.setText("无法保存账号或加密密码，请检查系统存储后重试");}
        }));dialog.setOnDismissListener(d->password.setText(""));dialog.show();
    }
    private String storeId(){try{return store.config().activeId;}catch(IOException e){return "";}}

    private void buildDevices(){renderedDevices=null;renderDevices();}
    private void requestDevices(boolean refresh){
        try{SettingsData config=store.config();WifiAccess.Link link=WifiAccess.actual(this,true);ConnectionStatus.Value state=ConnectionStatus.get();
            if(link==null||!ConnectionStatus.authenticated(state.state)||!state.key.equals(WifiAccess.key(link.network))||state.generation!=config.generation){toast("请先确认校园网已联网");return;}
            DeviceRepository.request(this,config,link.network,state.key,refresh);renderDevices();
        }catch(Exception e){showError(e);}
    }
    private void renderDevices(){
        DeviceRepository.Value data=DeviceRepository.get();ConnectionStatus.Value connection=ConnectionStatus.get();
        if(renderedDevices==data&&ConnectionStatus.authenticated(connection.state))return;
        renderedDevices=data;body.removeAllViews();button(body,"‹ 返回连接概览",false,()->navigate(0));body.addView(text("在线设备",24,true));
        boolean current=ConnectionStatus.authenticated(connection.state)&&data.key.equals(connection.key)&&data.generation==connection.generation;
        if(!current){body.addView(secondary("尚未取得当前连接的在线设备信息"));button(body,"读取在线设备",false,()->requestDevices(true));return;}
        body.addView(secondary("XHA在线账号："+RecoveryRepository.mask(data.account)));body.addView(secondary(data.loading?"正在读取…":data.message));
        body.addView(secondary("已加载"+data.devices.size()+"台"+(data.complete?" · 全部可见设备":" · 尚未全部读取")));
        button(body,"刷新设备列表",false,()->requestDevices(true));
        int owners=0;for(SessionInfoClient.Device d:data.devices)if(d.owner)owners++;
        for(SessionInfoClient.Device d:data.devices){LinearLayout row=card(body);row.addView(text(d.info.mobile+(d.owner&&owners==1?" · 本机":""),18,true));row.addView(secondary(d.info.ipv4));row.addView(secondary("上线时间："+d.info.since));
            TextView details=secondary("源IPv6："+d.info.ipv6+"\n源MAC："+d.info.mac+"\n移动端标识："+d.info.mobile);details.setTextIsSelectable(true);details.setVisibility(View.GONE);
            button(row,"展开设备详情",false,()->details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));row.addView(details);
        }
        if(!data.loading&&!data.complete)button(body,"加载更多 / 重试本页",false,()->requestDevices(false));
        if(data.fetchedAt>0)body.addView(secondary("本次进程内存快照 · "+time(System.currentTimeMillis()-Math.max(0,SystemClock.elapsedRealtime()-data.fetchedAt))+"读取"));
    }
    private void showDocument(String title,int resource){
        try(java.io.InputStream input=getResources().openRawResource(resource);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>24000)throw new IOException("说明文档过大");bytes.write(buffer,0,n);}
            TextView content=text(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8),16,false);content.setTextIsSelectable(true);content.setPadding(dp(20),dp(12),dp(20),dp(16));ScrollView page=new ScrollView(this);page.addView(content);
            new AlertDialog.Builder(this).setTitle(title).setView(page).setPositiveButton("关闭",null).show();
        }catch(IOException e){showError(e);}
    }
    private String updateMessage(){UpdateRepository.Value v=UpdateRepository.get();ReleaseMetadata release=v.release;return "当前版本："+BuildConfig.VERSION_NAME+"（"+BuildConfig.VERSION_CODE+"）\n\n"+v.message+(release==null?"":"\n\n发布版本："+release.name+"\n最低Android API："+release.minSdk+"\n"+release.notes)+"\n\n从GitHub下载正式APK后直接覆盖安装，无需先卸载。请保留原包名和签名。";}
    private void showUpdate(){
        LinearLayout form=dialogContent();updateText=secondary(updateMessage());form.addView(updateText);
        button(form,"重新检查版本",false,()->{UpdateRepository.request();updateText.setText(updateMessage());});
        button(form,"前往新版下载",true,()->{UpdateRepository.Value v=UpdateRepository.get();ReleaseMetadata r=v.release;if(v.loading||r==null||r.code<=BuildConfig.VERSION_CODE){toast("尚未确认可更新的Android正式版本");return;}if(Build.VERSION.SDK_INT<r.minSdk){toast("新版不支持当前Android版本");return;}openWeb(r.releaseUrl);});
        button(form,"打开GitHub发布页",false,()->openWeb(ReleaseMetadata.REPO+"/releases"));
        updateDialog=new AlertDialog.Builder(this).setTitle("检查更新").setView(scroll(form)).setNegativeButton("关闭",null).create();updateDialog.show();UpdateRepository.request();updateText.setText(updateMessage());
    }
    private void openWeb(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(RuntimeException e){toast("没有可用浏览器，请稍后从GitHub发布页下载");}}
    @Override public void onBackPressed(){if(tab==3){navigate(0);return;}super.onBackPressed();}
    private void showSettings(){
        LinearLayout form=dialogContent();permissionText=text(DeviceSettingsGuide.permissions(this),13,false);form.addView(permissionText);
        Spinner brand=picker(form,java.util.Arrays.asList(DeviceSettingsGuide.BRANDS));brand.setSelection(DeviceSettingsGuide.detected());
        TextView guide=text(DeviceSettingsGuide.guide(DeviceSettingsGuide.detected()),13,false);form.addView(guide);
        brand.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){guide.setText(DeviceSettingsGuide.guide(pos));}});
        button(form,"允许使用期间的精确位置",false,this::requestLocation);
        button(form,"打开系统定位设置",false,()->DeviceSettingsGuide.open(this,new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        button(form,"允许监测通知",false,()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);else DeviceSettingsGuide.appSettings(this);});
        button(form,"打开应用详情（权限与后台设置）",false,()->DeviceSettingsGuide.appSettings(this));
        button(form,"查看系统电池优化设置",false,()->DeviceSettingsGuide.open(this,new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        try{store.config();}catch(IOException e){button(form,"修复损坏配置（保留失败保护）",false,()->new AlertDialog.Builder(this).setTitle("重新建立本机配置？")
            .setMessage("损坏配置及已有密码密文会保留在应用私有存储中供排障；账号列表将重新建立，需重新输入凭据。失败保护不会清除。")
            .setPositiveButton("修复",(d,w)->{try{store.repairSettings();settingsDialog.dismiss();configurationChanged(false,"修复设置");}catch(Exception x){showError(x);}}).setNegativeButton("取消",null).show());}
        settingsDialog=new AlertDialog.Builder(this).setTitle("权限与品牌后台设置").setView(scroll(form)).setNegativeButton("关闭",null).create();settingsDialog.show();
    }
    private void requestLocation(){
        new AlertDialog.Builder(this).setTitle("读取真实Wi-Fi名称")
            .setMessage("读取Wi-Fi名称需要精确位置和系统定位；助手不读取GPS坐标。缺权限时顶部显示“没有网络或位置权限”。拒绝位置不影响已唯一确认Wi-Fi的校园识别和认证。")
            .setPositiveButton("授权",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},20)).setNegativeButton("稍后",null).show();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);CheckCoordinator.cancel();readOnly();}
    private void showLogs(){
        if(logsDialog!=null&&logsDialog.isShowing())return;
        LinearLayout content=dialogContent();logsHint=secondary("最新在前 · 自动更新");content.addView(logsHint);
        logsText=text("",14,false);logsText.setTextIsSelectable(true);logsScroll=scroll(logsText);
        content.addView(logsScroll,new LinearLayout.LayoutParams(-1,dp(360)));
        logsDialog=new AlertDialog.Builder(this).setTitle("诊断记录").setView(content).setPositiveButton("关闭",null).create();
        logsDialog.setOnDismissListener(d->{handler.removeCallbacks(logsTicker);logsDialog=null;logsText=null;logsScroll=null;logsHint=null;});
        logsRevision=-1;logsDialog.show();refreshLogs();handler.removeCallbacks(logsTicker);handler.postDelayed(logsTicker,1000);
    }
    private void refreshLogs(){
        if(logsText==null||logsScroll==null||logsRevision==store.logsRevision())return;
        final TextView text=logsText;final ScrollView scroll=logsScroll;String old=text.getText().toString();
        boolean top=scroll.getScrollY()<=dp(4);String anchor="";int inside=0;
        if(!top&&text.getLayout()!=null){int line=text.getLayout().getLineForVertical(scroll.getScrollY());int start=text.getLayout().getLineStart(line);anchor=old.substring(start,Math.min(old.length(),start+120));inside=scroll.getScrollY()-text.getLayout().getLineTop(line);}
        logsRevision=store.logsRevision();String latest=store.logsNewestFirst();int offset=anchor.isEmpty()?-1:latest.indexOf(anchor);final int target=offset,delta=inside;
        text.setText(latest);logsHint.setText(top?"最新在前 · 自动更新":"最新在前 · 有新记录，返回顶部查看");
        scroll.post(()->{if(scroll!=logsScroll)return;if(top)scroll.scrollTo(0,0);else if(target>=0&&text.getLayout()!=null)scroll.scrollTo(0,text.getLayout().getLineTop(text.getLayout().getLineForOffset(target))+delta);});
    }
    private void showError(Exception e){toast(e instanceof IOException||e instanceof IllegalArgumentException?e.getMessage():"操作失败，请检查系统存储与权限");refresh();}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){
        super.onResume();visible=true;CheckCoordinator.foreground(true);handler.post(ticker);handler.removeCallbacks(logsTicker);if(logsDialog!=null&&logsDialog.isShowing())handler.post(logsTicker);
        observer=new WifiAccess.Observer(this,()->handler.post(()->{if(visible){refresh();handler.removeCallbacks(networkEvent);handler.postDelayed(networkEvent,300);}}));
        handler.post(()->{if(visible){refresh();CheckCoordinator.request(this,RunGate.MANUAL,"启动连接",refreshed);}});
        if(store.enabled()&&!MonitorService.running)try{MonitorService.start(this);}catch(RuntimeException e){store.status("系统未允许恢复监测，请检查后台设置");}

    }
    @Override protected void onPause(){
        visible=false;CheckCoordinator.foreground(false);handler.removeCallbacks(logsTicker);handler.removeCallbacks(ticker);handler.removeCallbacks(networkEvent);if(observer!=null){observer.close();observer=null;}if(!store.enabled())CheckCoordinator.cancel();super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("tab",tab);state.putString("interval_draft",interval!=null?interval.getText().toString():intervalDraft);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(observer!=null)observer.close();if(settingsDialog!=null)settingsDialog.dismiss();if(updateDialog!=null)updateDialog.dismiss();if(logsDialog!=null)logsDialog.dismiss();super.onDestroy();}
}
