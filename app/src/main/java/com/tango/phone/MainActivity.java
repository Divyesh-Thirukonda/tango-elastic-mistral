package com.tango.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.animation.ValueAnimator;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.provider.MediaStore;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static java.lang.ref.WeakReference<MainActivity> foreground=new java.lang.ref.WeakReference<>(null);
    private static final int BG=Color.BLACK, CARD=0xFF0C0C0C, WHITE=Color.WHITE, MUTED=0xFF999999, BORDER=0xFF2A2A2A;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout container;
    private LinearLayout taskBox,taskActions;
    private TextView statusView,taskStatus,taskQuestion;
    private EditText command,reply;
    private Button mic,run,continueButton,pauseButton,stopButton,enableControl,taskDetails;
    private SpeechRecognizer recognizer;
    private boolean listening,settingsVisible,authVisible,signingIn,shouldListen,forceSystemRecognition,resumed;
    private ChatGptAuth auth;
    private TextView signInStatus;
    private String authMessage="";
    private String draftCommand="",draftReply="";
    private FrameLayout chatRoot,drawer,screenHost,composerOverlay;
    private View currentScreen,drawerScrim,overlayPanel;
    private String screenName="",attachmentSignature="",attachmentAccount="",attachmentThread="";
    private LinearLayout composer,attachmentChips;
    private HorizontalScrollView attachmentStrip;
    private Uri pendingCamera;
    private boolean importingAttachments,drawerDragging,drawerGestureCandidate,gestureStartedOpen;
    private float drawerProgress,gestureStartProgress;
    private int drawerWidth;
    private ValueAnimator drawerAnimation;
    private Runnable drawerNavigation;
    private ImageView transitionSnapshot;
    private VelocityTracker drawerVelocity;
    private static final int PICK_FILES=20,PICK_PHOTOS=21,TAKE_PHOTO=22;
    private AttachmentImportQueue attachmentImports;
    private String interruptedVoiceNotice="";
    private Runnable saveComposerDraft;
    private final AttachmentImportQueue.Listener importListener=this::onAttachmentImportsChanged;
    private LinearLayout messages,threadList,drawerPanel;
    private ScrollView conversation;
    private TextView threadTitle;
    private Button modelMode,accountMode;
    private String renderedThread="",drawerQuery="",lastThreadList="",lastChatMessages="";
    private boolean archivedThreads,drawerVisible;
    private float touchStartX,touchStartY;
    private final ChatGptAuth.Listener signInListener=new ChatGptAuth.Listener() {
        @Override public void onStatus(String message) {
            if(!alive())return;
            signingIn=auth.isSigningIn(); authMessage=message;
            if(signInStatus!=null&&authVisible) {signInStatus.setText(message);signInStatus.setVisibility(message.isBlank()?View.GONE:View.VISIBLE);}
        }
        @Override public void onSuccess() {
            if(!alive())return; signingIn=false;
            if(!resumed) {authMessage=auth.hasPlanAccess()?"":"Enable ChatGPT plan access to continue.";return;}
            if(!auth.hasPlanAccess()) { authMessage="Enable ChatGPT plan access to continue."; showSignIn(); return; }
            prefs.edit().putBoolean("configured",true).apply(); authMessage=""; showHome();
            requestNotifications();
        }
        @Override public void onError(String message) {
            if(!alive())return; signingIn=false; authMessage=message;
            if(!resumed)return;
            if(auth.hasPlanAccess()) {showSettings();errorDialog(message);} else showSignIn();
        }
    };
    private String lastRendered="";
    private Runnable scheduledRun;
    private int voiceGeneration;
    private boolean voiceFinishing;
    private int interactionDialogs;
    private final ArrayList<TangoSheet> activeSheets=new ArrayList<>();
    private boolean advancedExpanded,preferencesExpanded;
    private String pendingSheetKind="",pendingSheetThread="",pendingSheetDraft="";
    private EditText sheetEditor;
    private View transientNotice;
    private Runnable noticeDismiss;
    private int visibleMessageCount;
    private boolean chatHasRendered;
    private final Runnable refresh=new Runnable() { @Override public void run() { if(!settingsVisible&&!authVisible) renderTask(); handler.postDelayed(this,700); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs=getSharedPreferences("tango",0); auth=ChatGptAuth.get(this); AgentService.loadJournal(this);attachmentImports=AttachmentImportQueue.get(this);
        signingIn=auth.isSigningIn(); authMessage=auth.error(); auth.attachListener(signInListener);
        if(saved!=null){String camera=saved.getString("pendingCamera");pendingCamera=camera==null?null:Uri.parse(camera);attachmentAccount=saved.getString("attachmentAccount","");attachmentThread=saved.getString("attachmentThread","");}
        // Legacy computer/API credentials cannot bypass the new account-first flow.
        getSharedPreferences("secrets",0).edit().remove("bridge_token").remove("api_key").apply();
        prefs.edit().remove("provider").remove("bridge_url").remove("estimated_spend").apply();
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        showEntry();if(saved!=null){interruptedVoiceNotice=saved.getString("interruptedVoiceNotice","");restoreUiState(saved);}else handleLaunch(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        boolean ordinaryLauncher=Intent.ACTION_MAIN.equals(intent.getAction())&&!intent.getBooleanExtra("listen",false);
        if(ordinaryLauncher&&auth.hasPlanAccess()&&!auth.isSigningIn()&&currentScreen!=null){
            boolean composing=command!=null&&(command.hasFocus()||!command.getText().toString().isBlank())||AgentService.threadAttachments(this).length()>0;
            if(!composing&&!settingsVisible&&!authVisible&&!drawerVisible&&activeSheets.isEmpty()&&composerOverlay==null)handleLaunch(intent);
            return;
        }
        if(!signingIn)showEntry();handleLaunch(intent);
    }
    private void handleLaunch(Intent intent) {
        if(auth.isSigningIn()||!auth.hasPlanAccess()) { shouldListen=false; return; }
        boolean launch=intent.getBooleanExtra("listen",false) || Intent.ACTION_ASSIST.equals(intent.getAction()) || "android.intent.action.VOICE_ASSIST".equals(intent.getAction());
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && prefs.getBoolean("configured",false) && prefs.getBoolean("listen_on_launch",true)) launch=true;
        if(launch) { shouldListen=true; handler.postDelayed(() -> { if(shouldListen && resumed && alive() && !settingsVisible && !authVisible) { shouldListen=false; listen(); } },350); }
    }
    @Override protected void onResume() {
        super.onResume();foreground=new java.lang.ref.WeakReference<>(this); resumed=true; auth.attachListener(signInListener); auth.setForeground(true); signingIn=auth.isSigningIn();
        if(!signingIn&&!auth.error().isBlank())authMessage=auth.error();
        handler.removeCallbacks(refresh); handler.post(refresh);
        if(signingIn||!auth.hasPlanAccess())showSignIn();
        else if((authVisible&&!signingIn)||!prefs.getBoolean("configured",false)) {
            if(!auth.error().isBlank()) {showSettings();errorDialog(auth.error());}
            else signInListener.onSuccess();
        }
        else if(!settingsVisible&&!authVisible) { probe(); requestNotifications(); }
        syncUserInteraction();resumePendingVoice();
        resumeSheets();attachmentImports.attach(importListener);onAttachmentImportsChanged();
        String draftError=DraftSaveQueue.takeError(auth.activeClientId());if(!draftError.isBlank())toast(draftError);
        if(!interruptedVoiceNotice.isBlank()&&!settingsVisible&&!authVisible){voiceStatus(interruptedVoiceNotice);interruptedVoiceNotice="";}
    }
    @Override protected void onPause() {
        rememberDraft();DraftSaveQueue.flush();if(saveComposerDraft!=null){handler.removeCallbacks(saveComposerDraft);saveComposerDraft=null;}
        boolean queuedDrawerAction=drawerNavigation!=null;drawerNavigation=null;if(drawerAnimation!=null){drawerAnimation.cancel();drawerAnimation=null;}drawerDragging=false;drawerGestureCandidate=false;if(drawerVelocity!=null){drawerVelocity.recycle();drawerVelocity=null;}if(drawerVisible){if(queuedDrawerAction||drawerProgress>=.5f)setDrawerProgress(1);else closeDrawer(false);}
        if(listening)interruptedVoiceNotice="Recording stopped. Your draft is saved. Tap the mic to continue.";
        else if(scheduledRun!=null)interruptedVoiceNotice="Your voice draft is saved. Tap send when you're ready.";
        resumed=false;attachmentImports.detach(importListener);pauseSheets();AgentService.setUserInteracting(false);auth.setForeground(false);super.onPause();handler.removeCallbacks(refresh);
        if(listening)stopListening();else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)shouldListen=false;
        cancelScheduled();
    }
    @Override protected void onDestroy() { attachmentImports.detach(importListener);dismissSheetsImmediately();handler.removeCallbacksAndMessages(null); if(recognizer!=null)recognizer.destroy(); if(isFinishing()&&!isChangingConfigurations()&&auth.isSigningIn())auth.cancelSignIn(); if(drawerAnimation!=null)drawerAnimation.cancel();if(transitionSnapshot!=null){transitionSnapshot.animate().cancel();transitionSnapshot.setImageDrawable(null);}if(currentScreen!=null)currentScreen.animate().cancel();network.shutdownNow(); super.onDestroy(); }
    private void showEntry() { if(auth.isSigningIn()||!auth.hasPlanAccess())showSignIn(); else showHome(); }
    private void requestNotifications() { if(resumed&&auth.hasPlanAccess()&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!prefs.getBoolean("notification_requested",false)) { prefs.edit().putBoolean("notification_requested",true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11); } }
    private void shell() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        container=new LinearLayout(this); container.setOrientation(LinearLayout.VERTICAL); container.setPadding(dp(24),dp(20),dp(24),dp(24));
        scroll.addView(container,new ScrollView.LayoutParams(-1,-2)); presentScreen(scroll,settingsVisible?"account":"signin");
        scroll.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            scroll.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });scroll.requestApplyInsets();
    }
    private void showHome() {
        if(!auth.hasPlanAccess()) { showSignIn(); return; }
        rememberDraft();dismissSheetsImmediately();cancelScheduled();stopListening();dismissComposerOverlay(false);closeDrawer(false);
        AgentService.loadJournal(this);
        renderedThread=AgentService.activeThreadId(this);
        draftCommand=AgentService.threadDraft(this);draftReply=AgentService.threadReplyDraft(this);
        authVisible=false; settingsVisible=false; lastRendered="";lastChatMessages="";visibleMessageCount=0;chatHasRendered=false;
        chatRoot=new FrameLayout(this);chatRoot.setTag(renderedThread);chatRoot.setBackgroundColor(BG);
        container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);
        container.setFocusableInTouchMode(true);container.requestFocus();
        chatRoot.addView(container,new FrameLayout.LayoutParams(-1,-1));presentScreen(chatRoot,"chat");
        chatRoot.setOnApplyWindowInsetsListener((v,insets) -> {
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;
        });chatRoot.requestApplyInsets();
        LinearLayout header=row();header.setPadding(dp(12),dp(8),dp(12),dp(8));
        Button menu=roundIcon(R.drawable.ic_menu,false,"Open threads");menu.setOnClickListener(v -> {UiMotion.tick(v);openDrawer();});
        header.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
        threadTitle=text("Tango",17,WHITE,true);threadTitle.setGravity(Gravity.CENTER);threadTitle.setSingleLine(true);threadTitle.setEllipsize(TextUtils.TruncateAt.END);threadTitle.setPadding(dp(12),0,dp(12),0);
        header.addView(threadTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button account=roundIcon(R.drawable.ic_account,false,"Account");account.setOnClickListener(v -> showSettings());header.addView(account,new LinearLayout.LayoutParams(dp(48),dp(48)));container.addView(header);
        conversation=new ScrollView(this);conversation.setFillViewport(true);conversation.setClipToPadding(false);conversation.setPadding(dp(20),dp(20),dp(20),dp(12));
        LinearLayout chatContent=new LinearLayout(this);chatContent.setOrientation(LinearLayout.VERTICAL);
        messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);chatContent.addView(messages,new LinearLayout.LayoutParams(-1,-2));
        taskBox=new LinearLayout(this);taskBox.setOrientation(LinearLayout.VERTICAL);taskBox.setPadding(0,dp(12),0,dp(8));
        taskStatus=text("",14,MUTED,false);taskBox.addView(taskStatus);
        taskQuestion=text("",17,WHITE,false);taskQuestion.setPadding(0,dp(12),0,dp(8));taskQuestion.setTextIsSelectable(true);taskBox.addView(taskQuestion);
        reply=chatInput("Reply to Tango",draftReply);reply.setTextSize(16);taskBox.addView(reply,new LinearLayout.LayoutParams(-1,-2));
        taskActions=row();continueButton=button("Continue",true);continueButton.setBackground(background(WHITE,24));continueButton.setOnClickListener(v -> continueTask());taskActions.addView(continueButton,new LinearLayout.LayoutParams(0,dp(44),1));
        pauseButton=button("Pause",false);pauseButton.setBackground(background(0xFF242424,24));pauseButton.setOnClickListener(v -> {UiMotion.confirm(v);sendService("pause",null);});taskActions.addView(pauseButton,new LinearLayout.LayoutParams(0,dp(44),1));
        stopButton=button("Stop",false);stopButton.setBackground(background(0xFF242424,24));stopButton.setOnClickListener(v -> {UiMotion.confirm(v);sendService("stop",null);});LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(0,dp(44),1);stopParams.leftMargin=dp(8);taskActions.addView(stopButton,stopParams);spaceIn(taskBox,10);taskBox.addView(taskActions);
        taskDetails=button("Task details",false);taskDetails.setTextColor(MUTED);taskDetails.setTextSize(12);taskDetails.setBackgroundColor(BG);taskDetails.setOnClickListener(v -> showTaskDetails());taskBox.addView(taskDetails,new LinearLayout.LayoutParams(-2,dp(40)));
        chatContent.addView(taskBox,new LinearLayout.LayoutParams(-1,-2));conversation.addView(chatContent,new ScrollView.LayoutParams(-1,-2));container.addView(conversation,new LinearLayout.LayoutParams(-1,0,1));
        enableControl=button("Enable phone control",false);enableControl.setBackground(background(0xFF202020,24));enableControl.setOnClickListener(v -> permissionDisclosure());LinearLayout.LayoutParams setupParams=new LinearLayout.LayoutParams(-1,dp(44));setupParams.setMargins(dp(12),0,dp(12),dp(8));container.addView(enableControl,setupParams);
        composer=new LinearLayout(this);composer.setOrientation(LinearLayout.VERTICAL);composer.setPadding(dp(16),dp(12),dp(10),dp(8));composer.setBackground(background(0xFF2D2D2D,28));
        attachmentStrip=new HorizontalScrollView(this);attachmentStrip.setHorizontalScrollBarEnabled(false);attachmentChips=row();attachmentStrip.addView(attachmentChips);composer.addView(attachmentStrip,new LinearLayout.LayoutParams(-1,-2));attachmentSignature="";
        command=chatInput("Ask Tango to do anything",draftCommand);command.setTextSize(18);command.setMinLines(1);command.setMaxLines(6);command.setPadding(dp(2),dp(4),dp(6),dp(8));composer.addView(command,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=row();Button add=roundIcon(R.drawable.ic_plus,false,"Add to chat");add.setBackgroundColor(Color.TRANSPARENT);add.setOnClickListener(v -> composerOptions());controls.addView(add,new LinearLayout.LayoutParams(dp(44),dp(44)));
        controls.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        modelMode=roundIcon(R.drawable.ic_effort,false,"Choose model mode");modelMode.setBackgroundColor(Color.TRANSPARENT);modelMode.setOnClickListener(v -> {UiMotion.tick(v);showModelModes();});controls.addView(modelMode,new LinearLayout.LayoutParams(dp(44),dp(44)));
        mic=roundIcon(R.drawable.ic_mic,false,"Speak a command");mic.setBackgroundColor(Color.TRANSPARENT);mic.setOnClickListener(v -> {UiMotion.tick(v);cancelScheduled();if(listening)finishListening();else listen();});controls.addView(mic,new LinearLayout.LayoutParams(dp(44),dp(44)));spaceHorizontal(controls,4);
        run=roundIcon(R.drawable.ic_send,true,"Run command");run.setOnClickListener(v -> {UiMotion.confirm(v);cancelScheduled();runCommand(command.getText().toString());});controls.addView(run,new LinearLayout.LayoutParams(dp(40),dp(40)));composer.addView(controls);
        LinearLayout.LayoutParams composerParams=new LinearLayout.LayoutParams(-1,-2);composerParams.setMargins(dp(10),0,dp(10),dp(10));container.addView(composer,composerParams);
        statusView=text("",12,MUTED,false);statusView.setGravity(Gravity.CENTER);statusView.setPadding(dp(16),0,dp(16),dp(10));statusView.setVisibility(View.GONE);container.addView(statusView);
        watchComposerDraft(command);watchComposerDraft(reply);updateModelMode();updateMicrophone();renderAttachments();renderTask();probe();syncUserInteraction();
    }
    private EditText chatInput(String hint,String draft) {
        EditText input=new EditText(this);input.setTextColor(WHITE);input.setHintTextColor(MUTED);input.setTextSize(17);input.setHint(hint);input.setGravity(Gravity.TOP);input.setMaxLines(5);input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(0,dp(8),0,dp(8));
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setText(draft);input.setOnFocusChangeListener((v,focus) -> {if(focus)cancelScheduled();syncUserInteraction();});return input;
    }
    private void watchComposerDraft(EditText input) {
        final String account=auth.activeClientId(),thread=renderedThread;
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){
                if(saveComposerDraft!=null)handler.removeCallbacks(saveComposerDraft);
                saveComposerDraft=() -> {saveComposerDraft=null;if(alive()&&!settingsVisible&&!authVisible&&account.equals(auth.activeClientId())&&thread.equals(renderedThread)&&thread.equals(AgentService.activeThreadId(MainActivity.this)))rememberDraft();};
                handler.postDelayed(saveComposerDraft,180);
            }
            public void afterTextChanged(Editable value){}
        });
    }
    private void renderTask() {
        if(authVisible||settingsVisible)return;
        if(!auth.hasPlanAccess()) {stopListening();cancelScheduled();showSignIn();return;}
        if(taskStatus==null)return;
        String current=AgentService.activeThreadId(this);
        if(!current.equals(renderedThread)){renderedThread="";showHome();return;}
        if(enableControl!=null)enableControl.setVisibility(PhoneAccessibilityService.instance==null?View.VISIBLE:View.GONE);
        if(drawerVisible)renderThreads();renderAttachments();
        JSONArray entries=AgentService.journalSnapshot();String stamp=current+AgentService.state+AgentService.status+AgentService.question+AgentService.steps+entries.toString();if(stamp.equals(lastRendered))return;lastRendered=stamp;
        String state=AgentService.state;
        String title="Tango";JSONArray threads=AgentService.listThreads(this,"",false);for(int i=0;i<threads.length();i++){JSONObject t=threads.optJSONObject(i);if(t!=null&&current.equals(t.optString("id"))&&!AgentService.goal.isBlank())title=t.optString("title","Tango");}threadTitle.setText(title);
        renderMessages(entries);taskBox.setVisibility(state.equals("idle")?View.GONE:View.VISIBLE);
        UiMotion.crossfade(taskStatus,state.equals("waiting")?"Needs you":state.equals("done")?"Completed":AgentService.status);
        boolean questionInChat=false;for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e!=null&&e.optString("who").equals("Needs you")&&e.optString("text").equals(AgentService.question))questionInChat=true;}taskQuestion.setText(AgentService.question);taskQuestion.setVisibility(AgentService.question.isBlank()||questionInChat?View.GONE:View.VISIBLE);
        boolean active=state.equals("running"),resumable=state.equals("waiting")||state.equals("paused");
        reply.setVisibility(View.GONE);continueButton.setVisibility(resumable?View.VISIBLE:View.GONE);pauseButton.setVisibility(active?View.VISIBLE:View.GONE);stopButton.setVisibility(active||resumable?View.VISIBLE:View.GONE);taskActions.setVisibility(active||resumable?View.VISIBLE:View.GONE);
        taskDetails.setVisibility(!state.equals("idle")||!AgentService.goal.isBlank()||AgentService.journal.length()>0?View.VISIBLE:View.GONE);
        command.setHint(resumable?"Reply to Tango":AgentService.goal.isBlank()?"Ask Tango to do anything":"Message Tango");run.setContentDescription(active?"Update command":"Run command");run.setTooltipText(run.getContentDescription());
    }
    private void renderMessages(JSONArray journal) {
        StringBuilder signature=new StringBuilder();for(int i=0;i<journal.length();i++){JSONObject e=journal.optJSONObject(i);if(e!=null&&(e.optString("who").equals("You")||e.optString("who").equals("Result")||e.optString("who").equals("Needs you")))signature.append(e.toString());}String next=signature.toString();if(next.equals(lastChatMessages))return;int previousCount=visibleMessageCount;boolean animateNew=chatHasRendered;visibleMessageCount=0;chatHasRendered=true;
        boolean atBottom=!conversation.canScrollVertically(1)||lastChatMessages.isBlank();int scrollY=conversation.getScrollY();lastChatMessages=next;messages.removeAllViews();
        for(int i=0;i<journal.length();i++) {
            JSONObject entry=journal.optJSONObject(i);if(entry==null)continue;String who=entry.optString("who");
            if(!who.equals("You")&&!who.equals("Result")&&!who.equals("Needs you"))continue;
            boolean user=who.equals("You");
            JSONArray attached=entry.optJSONArray("attachments");if(attached!=null&&attached.length()>0){LinearLayout files=new LinearLayout(this);files.setOrientation(LinearLayout.VERTICAL);files.setGravity(Gravity.RIGHT);for(int f=0;f<attached.length();f++){JSONObject item=attached.optJSONObject(f);if(item!=null)addAttachmentChip(files,item,false);}messages.addView(files,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout line=row();line.setGravity(user?Gravity.RIGHT:Gravity.LEFT);
            TextView message=text(entry.optString("text"),17,WHITE,false);message.setTextIsSelectable(true);message.setLineSpacing(dp(3),1);message.setMaxWidth((int)(getResources().getDisplayMetrics().widthPixels*.8));
            if(user){message.setPadding(dp(16),dp(12),dp(16),dp(12));message.setBackground(background(0xFF282828,24));}
            else message.setPadding(0,dp(12),0,dp(12));line.addView(message,new LinearLayout.LayoutParams(-2,-2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(20);messages.addView(line,lp);if(animateNew&&visibleMessageCount>=previousCount)UiMotion.reveal(line,Math.min(120,(visibleMessageCount-previousCount)*35));visibleMessageCount++;
        }
        conversation.post(() -> {if(atBottom)conversation.fullScroll(View.FOCUS_DOWN);else conversation.scrollTo(0,scrollY);});
    }
    private void updateModelMode() {String name=ApiClient.modeName(new ApiClient(this).selectedMode());if(modelMode!=null){modelMode.setText("");modelMode.setContentDescription("Choose model mode, "+name);modelMode.setTooltipText(modelMode.getContentDescription());}if(accountMode!=null)accountMode.setText("Model mode · "+name);}
    private void showModelModes() {
        cancelScheduled();stopListening();
        ApiClient api=new ApiClient(this);EffortPickerView picker=new EffortPickerView(this);picker.setMode(api.selectedMode());
        picker.setOnModeChangedListener(mode -> {api.setMode(mode);updateModelMode();});
        showComposerOverlay(picker,true);
    }
    private void composerOptions() {
        cancelScheduled();stopListening();
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(10),dp(10),dp(10),dp(10));panel.setBackground(background(0xFF272727,28));
        attachmentOption(panel,"Camera",R.drawable.ic_camera,TAKE_PHOTO);
        attachmentOption(panel,"Photos",R.drawable.ic_photo,PICK_PHOTOS);
        attachmentOption(panel,"Files",R.drawable.ic_file,PICK_FILES);
        showComposerOverlay(panel,false);
    }
    private void attachmentOption(LinearLayout panel,String label,int icon,int request) {
        Button option=button(label,false);option.setTextSize(18);option.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);option.setBackgroundColor(Color.TRANSPARENT);option.setCompoundDrawablesWithIntrinsicBounds(icon,0,0,0);option.setCompoundDrawablePadding(dp(18));option.setOnClickListener(v -> {dismissComposerOverlay(false);launchAttachment(request);});panel.addView(option,new LinearLayout.LayoutParams(-1,dp(58)));
    }
    private void showComposerOverlay(View panel,boolean effort) {
        dismissComposerOverlay(false);
        FrameLayout parent=!settingsVisible&&!authVisible?chatRoot:screenHost;if(parent==null)return;
        composerOverlay=new FrameLayout(this);View scrim=new View(this);scrim.setBackgroundColor(0xAA000000);scrim.setContentDescription("Dismiss options");scrim.setOnClickListener(v -> dismissComposerOverlay(true));composerOverlay.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        if(parent==screenHost)composerOverlay.setOnApplyWindowInsetsListener((v,insets) -> {android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()),ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;});
        Button close=roundIcon(R.drawable.ic_close,false,"Close options");close.setOnClickListener(v -> dismissComposerOverlay(true));FrameLayout.LayoutParams closeParams=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.RIGHT);closeParams.setMargins(0,dp(12),dp(12),0);composerOverlay.addView(close,closeParams);
        overlayPanel=panel;int width=effort?-1:Math.min(dp(300),getResources().getDisplayMetrics().widthPixels-dp(24));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,-2,Gravity.BOTTOM|(effort?Gravity.CENTER_HORIZONTAL:Gravity.LEFT));lp.leftMargin=dp(12);lp.rightMargin=dp(12);
        lp.bottomMargin=!settingsVisible&&composer!=null?composer.getHeight()+dp(22):dp(40);composerOverlay.addView(panel,lp);panel.setClickable(true);parent.addView(composerOverlay,new FrameLayout.LayoutParams(-1,-1));composerOverlay.requestApplyInsets();
        if(UiMotion.enabled()){composerOverlay.setAlpha(0);composerOverlay.animate().alpha(1).setDuration(UiMotion.STATE).start();panel.setTranslationY(dp(18));panel.setScaleX(.97f);panel.setScaleY(.97f);panel.animate().translationY(0).scaleX(1).scaleY(1).setDuration(UiMotion.ENTER).setInterpolator(UiMotion.EASE).start();}
    }
    private void dismissComposerOverlay(boolean animate) {
        if(composerOverlay==null)return;FrameLayout old=composerOverlay;composerOverlay=null;overlayPanel=null;old.animate().cancel();
        Runnable remove=() -> {if(old.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)old.getParent()).removeView(old);};
        if(animate&&UiMotion.enabled())old.animate().alpha(0).translationY(dp(10)).setDuration(UiMotion.EXIT).setInterpolator(UiMotion.EASE).withEndAction(remove).start();else remove.run();
    }
    private void presentScreen(View next,String name) {
        dismissComposerOverlay(false);
        if(screenHost==null){screenHost=new FrameLayout(this);screenHost.setBackgroundColor(BG);setContentView(screenHost);}
        if(transitionSnapshot!=null){transitionSnapshot.animate().cancel();transitionSnapshot.setImageDrawable(null);transitionSnapshot=null;}
        View old=currentScreen;if(old!=null){old.animate().cancel();old.setAlpha(1);old.setTranslationX(0);old.setScaleX(1);old.setScaleY(1);}String previous=screenName;currentScreen=next;screenName=name;
        boolean animate=UiMotion.enabled()&&old!=null&&!name.equals("signin")&&!previous.equals("signin")&&(!previous.equals(name)||name.equals("chat")&&!java.util.Objects.equals(old.getTag(),next.getTag()))&&old.getWidth()>0;
        ImageView snapshot=null;
        if(animate){try{android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(old.getWidth(),old.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);old.draw(new android.graphics.Canvas(bitmap));snapshot=new ImageView(this);snapshot.setImageBitmap(bitmap);snapshot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}catch(OutOfMemoryError ignored){}}
        screenHost.removeAllViews();screenHost.addView(next,new FrameLayout.LayoutParams(-1,-1));
        if(snapshot!=null){
            ImageView outgoing=snapshot;transitionSnapshot=outgoing;screenHost.addView(outgoing,new FrameLayout.LayoutParams(-1,-1));
            float direction=name.equals("account")?1:-1;
            next.setTranslationX(direction*dp(56));next.setAlpha(.25f);next.setScaleX(.985f);next.setScaleY(.985f);
            next.animate().translationX(0).alpha(1).scaleX(1).scaleY(1).setStartDelay(0).setDuration(UiMotion.ENTER).setInterpolator(UiMotion.EASE).start();
            outgoing.animate().translationX(-direction*dp(28)).alpha(0).scaleX(.985f).scaleY(.985f).setStartDelay(0).setDuration(UiMotion.EXIT+30).setInterpolator(UiMotion.EASE).withEndAction(() -> {screenHost.removeView(outgoing);outgoing.setImageDrawable(null);if(transitionSnapshot==outgoing)transitionSnapshot=null;}).start();
        }
    }
    private void launchAttachment(int request) {
        importingAttachments=attachmentImports.state(auth.activeClientId(),AgentService.activeThreadId(this)).busy;
        if(importingAttachments){toast("Adding your files…");return;}
        if(!auth.hasPlanAccess()){showSignIn();return;}
        rememberDraft();cancelScheduled();stopListening();hideKeyboard();
        if(AgentService.threadAttachments(this).length()>=AttachmentStore.MAX_FILES){toast("Add up to 4 files per message.");return;}
        attachmentAccount=auth.activeClientId();attachmentThread=AgentService.activeThreadId(this);
        if(AgentService.instance!=null&&AgentService.state.equals("running"))AgentService.instance.pauseTask("Add your files, then continue.");
        try {
            Intent intent;
            if(request==TAKE_PHOTO){
                pendingCamera=AttachmentStore.beginCamera(this);intent=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,pendingCamera);intent.setClipData(ClipData.newRawUri("Camera photo",pendingCamera));intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(request==PICK_PHOTOS?"image/*":"*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).putExtra(Intent.EXTRA_MIME_TYPES,request==PICK_PHOTOS?new String[]{"image/jpeg","image/png","image/webp"}:AttachmentStore.SUPPORTED_MIME_TYPES).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            }
            startActivityForResult(intent,request);
        }catch(Exception error){if(pendingCamera!=null){try{AttachmentStore.finishCamera(this,pendingCamera,false);}catch(Exception ignored){}pendingCamera=null;}errorDialog(request==TAKE_PHOTO?"Camera could not open. Enable a camera app, or choose Photos.":"Files could not open. Enable your device's file picker and try again.");}
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        rememberDraft();DraftSaveQueue.flush();saveUiState(out);out.putString("interruptedVoiceNotice",listening?"Recording stopped. Your draft is saved. Tap the mic to continue.":scheduledRun!=null?"Your voice draft is saved. Tap send when you're ready.":interruptedVoiceNotice);super.onSaveInstanceState(out);if(pendingCamera!=null)out.putString("pendingCamera",pendingCamera.toString());out.putString("attachmentAccount",attachmentAccount);out.putString("attachmentThread",attachmentThread);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=PICK_FILES&&request!=PICK_PHOTOS&&request!=TAKE_PHOTO)return;
        final Uri camera=request==TAKE_PHOTO?pendingCamera:null;if(request==TAKE_PHOTO)pendingCamera=null;
        ArrayList<Uri> selected=new ArrayList<>();if(result==RESULT_OK&&request!=TAKE_PHOTO&&data!=null){ClipData clip=data.getClipData();if(clip!=null){for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null&&!selected.contains(uri))selected.add(uri);}}else if(data.getData()!=null)selected.add(data.getData());}
        if(camera==null&&selected.isEmpty())return;
        attachmentImports.enqueue(attachmentAccount,attachmentThread,selected,camera,result==RESULT_OK);onAttachmentImportsChanged();
    }
    private void onAttachmentImportsChanged() {
        if(!alive()||!resumed)return;
        attachmentSignature="";renderAttachments();
        if(!settingsVisible&&!authVisible){String message=attachmentImports.consumeMessage(auth.activeClientId(),renderedThread);if(!message.isBlank())toast(message);}
    }
    private void renderAttachments() {
        if(attachmentChips==null||settingsVisible||authVisible)return;AttachmentImportQueue.State importing=attachmentImports.state(auth.activeClientId(),renderedThread);importingAttachments=importing.busy;
        if(resumed&&!importing.busy&&!importing.message.isBlank()){String message=attachmentImports.consumeMessage(auth.activeClientId(),renderedThread);if(!message.isBlank())toast(message);}
        JSONArray items=AgentService.threadAttachments(this);String signature=items.toString()+importingAttachments+importing.completed+importing.canceling;
        if(signature.equals(attachmentSignature))return;attachmentSignature=signature;attachmentChips.removeAllViews();attachmentStrip.setVisibility(items.length()>0||importingAttachments?View.VISIBLE:View.GONE);
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null)addAttachmentChip(attachmentChips,item,true);}
        if(importingAttachments){LinearLayout progress=row();TextView loading=text(importing.caption(),14,MUTED,false);loading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);loading.setPadding(dp(10),dp(14),dp(10),dp(14));progress.addView(loading);Button cancel=button("Cancel",false);cancel.setContentDescription("Cancel import");cancel.setEnabled(!importing.canceling);cancel.setOnClickListener(v -> {UiMotion.confirm(v);attachmentImports.cancel(auth.activeClientId(),renderedThread);renderAttachments();});progress.addView(cancel,new LinearLayout.LayoutParams(-2,dp(44)));attachmentChips.addView(progress);}
        if(run!=null)run.setEnabled(!importingAttachments);
    }
    private void addAttachmentChip(LinearLayout target,JSONObject item,boolean removable) {
        LinearLayout chip=row();chip.setPadding(dp(8),dp(6),dp(4),dp(6));chip.setBackground(background(0xFF393939,16));String name=item.optString("name","Attachment"),id=item.optString("id");
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setImageResource(item.optString("mime").startsWith("image/")?R.drawable.ic_photo:R.drawable.ic_file);preview.setBackground(background(0xFF303030,9));preview.setClipToOutline(true);chip.addView(preview,new LinearLayout.LayoutParams(dp(42),dp(42)));
        if(item.optString("mime").startsWith("image/")){String thread=renderedThread,account=auth.activeClientId();android.content.Context app=getApplicationContext();int pixels=dp(80);network.execute(() -> {android.graphics.Bitmap bitmap=AttachmentStore.thumbnail(app,item,pixels);handler.post(() -> {if(bitmap!=null&&alive()&&thread.equals(renderedThread)&&account.equals(auth.activeClientId())&&preview.isAttachedToWindow())preview.setImageBitmap(bitmap);else if(bitmap!=null)bitmap.recycle();});});}
        TextView title=text(name,14,WHITE,false);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);title.setMaxWidth(dp(140));title.setPadding(dp(10),0,dp(8),0);chip.addView(title,new LinearLayout.LayoutParams(-2,-2));
        if(removable){Button remove=roundIcon(R.drawable.ic_close,false,"Remove "+name);remove.setBackgroundColor(Color.TRANSPARENT);remove.setOnClickListener(v -> {try{AgentService.removeThreadAttachment(this,id);renderAttachments();}catch(Exception error){errorDialog(error.getMessage());}});chip.addView(remove,new LinearLayout.LayoutParams(dp(36),dp(42)));}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.rightMargin=dp(8);lp.bottomMargin=dp(8);target.addView(chip,lp);
    }
    private void newChat() {
        if(drawerVisible){navigateFromDrawer(this::newChat);return;}
        rememberDraft();cancelScheduled();stopListening();
        try {AgentService.newThread(this);renderedThread="";draftCommand="";draftReply="";showHome();}catch(Exception e){errorDialog(e.getMessage());}
    }
    private void openDrawer() {openDrawer(true);}
    private void openDrawer(boolean animate) {
        if(drawerVisible||chatRoot==null)return;dismissComposerOverlay(false);rememberDraft();cancelScheduled();stopListening();hideKeyboard();
        drawerVisible=true;drawerQuery="";lastThreadList="";archivedThreads=false;drawer=new FrameLayout(this);
        View scrim=new View(this);drawerScrim=scrim;scrim.setBackgroundColor(0x99000000);scrim.setOnClickListener(v -> closeDrawer(true));scrim.setContentDescription("Close threads");drawer.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        int width=Math.min(dp(360),(int)(getResources().getDisplayMetrics().widthPixels*.84));drawerWidth=width;
        drawerPanel=new LinearLayout(this);drawerPanel.setOrientation(LinearLayout.VERTICAL);drawerPanel.setPadding(dp(24),dp(8),dp(24),dp(16));drawerPanel.setBackgroundColor(0xFF202020);
        drawer.addView(drawerPanel,new FrameLayout.LayoutParams(width,-1,Gravity.LEFT));chatRoot.addView(drawer,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=row();heading.addView(text("Tango",24,WHITE,true),new LinearLayout.LayoutParams(0,-2,1));Button search=roundIcon(R.drawable.ic_search,false,"Search chats");search.setOnClickListener(v -> showThreadSearch());heading.addView(search,new LinearLayout.LayoutParams(dp(48),dp(48)));Button close=roundIcon(R.drawable.ic_close,false,"Close threads");close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v -> closeDrawer(true));heading.addView(close,new LinearLayout.LayoutParams(dp(44),dp(48)));drawerPanel.addView(heading);spaceIn(drawerPanel,22);
        Button connect=button("Connect to Codex",false);connect.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);connect.setTextSize(17);connect.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_connect,0,0,0);connect.setCompoundDrawablePadding(dp(14));connect.setBackgroundColor(Color.TRANSPARENT);connect.setPadding(0,0,0,0);connect.setOnClickListener(v -> navigateFromDrawer(this::showCodexConnection));drawerPanel.addView(connect,new LinearLayout.LayoutParams(-1,dp(52)));
        spaceIn(drawerPanel,20);View divider=new View(this);divider.setBackgroundColor(0xFF404040);drawerPanel.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));spaceIn(drawerPanel,16);
        LinearLayout listHeader=row();TextView chats=text("Chats",12,MUTED,false);listHeader.addView(chats,new LinearLayout.LayoutParams(0,-2,1));Button archive=button("Archived",false);archive.setTextSize(12);archive.setTextColor(MUTED);archive.setBackgroundColor(Color.TRANSPARENT);archive.setPadding(0,0,0,0);archive.setOnClickListener(v -> {archivedThreads=!archivedThreads;archive.setText(archivedThreads?"Back to chats":"Archived");chats.setText(archivedThreads?"Archived chats":"Chats");lastThreadList="";renderThreads();});listHeader.addView(archive,new LinearLayout.LayoutParams(-2,dp(40)));drawerPanel.addView(listHeader);
        ScrollView scroll=new ScrollView(this);threadList=new LinearLayout(this);threadList.setOrientation(LinearLayout.VERTICAL);scroll.addView(threadList);drawerPanel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=row();Button fresh=button("Chat",false);fresh.setTextSize(17);fresh.setTextColor(WHITE);fresh.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_compose,0,0,0);fresh.setCompoundDrawablePadding(dp(10));fresh.setBackground(background(0xFF3564F4,28));fresh.setContentDescription("New chat");fresh.setOnClickListener(v -> newChat());bottom.addView(fresh,new LinearLayout.LayoutParams(dp(132),dp(52)));bottom.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));Button account=roundIcon(R.drawable.ic_account,false,"Account");account.setOnClickListener(v -> showSettings());bottom.addView(account,new LinearLayout.LayoutParams(dp(48),dp(48)));drawerPanel.addView(bottom);
        renderThreads();setDrawerProgress(0);if(animate)animateDrawer(true);
    }
    private void navigateFromDrawer(Runnable navigation) {if(drawer==null){navigation.run();return;}drawerNavigation=navigation;animateDrawer(false);}
    private void closeDrawer(boolean animate) {
        if(drawer==null){drawerVisible=false;return;}
        if(animate){animateDrawer(false);return;}
        if(drawerAnimation!=null){drawerAnimation.cancel();drawerAnimation=null;}
        drawerNavigation=null;FrameLayout old=drawer;setDrawerProgress(0);drawer=null;drawerPanel=null;drawerScrim=null;drawerVisible=false;
        if(old.getParent() instanceof FrameLayout)((FrameLayout)old.getParent()).removeView(old);
    }
    private void setDrawerProgress(float value) {
        drawerProgress=Math.max(0,Math.min(1,value));if(drawerPanel!=null)drawerPanel.setTranslationX(-drawerWidth*(1-drawerProgress));if(drawerScrim!=null)drawerScrim.setAlpha(drawerProgress);
    }
    private void animateDrawer(boolean open) {
        if(drawerAnimation!=null)drawerAnimation.cancel();float target=open?1:0;
        if(!UiMotion.enabled()||Math.abs(target-drawerProgress)<.001f){drawerAnimation=null;setDrawerProgress(target);if(!open){Runnable navigation=drawerNavigation;closeDrawer(false);if(navigation!=null&&alive())navigation.run();}return;}
        ValueAnimator animation=ValueAnimator.ofFloat(drawerProgress,target);drawerAnimation=animation;animation.setDuration(Math.max(140,(long)((open?UiMotion.ENTER:UiMotion.EXIT)*Math.sqrt(Math.abs(target-drawerProgress)))));animation.setInterpolator(UiMotion.EASE);animation.addUpdateListener(a -> setDrawerProgress((Float)a.getAnimatedValue()));
        animation.addListener(new android.animation.AnimatorListenerAdapter(){private boolean cancelled;@Override public void onAnimationCancel(android.animation.Animator a){cancelled=true;}@Override public void onAnimationEnd(android.animation.Animator a){if(!cancelled&&drawerAnimation==a){drawerAnimation=null;if(!open){Runnable navigation=drawerNavigation;closeDrawer(false);if(navigation!=null&&alive())navigation.run();}}}});animation.start();
    }
    private void renderThreads() {
        if(!drawerVisible||threadList==null)return;JSONArray list=AgentService.listThreads(this,drawerQuery,archivedThreads);String stamp=list.toString()+drawerQuery+archivedThreads;if(stamp.equals(lastThreadList))return;lastThreadList=stamp;threadList.removeAllViews();
        if(list.length()==0){TextView empty=text(drawerQuery.isBlank()?(archivedThreads?"No archived chats":"Your chats will appear here"):"No chats found",14,MUTED,false);empty.setPadding(0,dp(18),0,0);threadList.addView(empty);return;}
        for(int i=0;i<list.length();i++){
            JSONObject thread=list.optJSONObject(i);if(thread==null)continue;String id=thread.optString("id"),title=thread.optString("title","New chat");
            LinearLayout line=row();line.setBackground(thread.optBoolean("active")?background(0xFF303030,16):background(Color.TRANSPARENT,16));
            Button item=button(title,false);item.setTextSize(17);item.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));item.setSingleLine(true);item.setEllipsize(TextUtils.TruncateAt.END);item.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);item.setPadding(dp(10),0,dp(6),0);item.setBackgroundColor(Color.TRANSPARENT);item.setContentDescription(title+(thread.optBoolean("active")?", current chat":""));item.setOnClickListener(v -> {UiMotion.tick(v);selectChat(id);});item.setOnLongClickListener(v -> {UiMotion.confirm(v);threadOptions(thread);return true;});line.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));
            Button more=roundIcon(R.drawable.ic_more,false,"Options for "+title);more.setBackgroundColor(Color.TRANSPARENT);more.setOnClickListener(v -> threadOptions(thread));line.addView(more,new LinearLayout.LayoutParams(dp(44),dp(48)));threadList.addView(line,new LinearLayout.LayoutParams(-1,-2));
        }
    }

    private void showThreadSearch() {
        EditText search=chatInput("Search chats",drawerQuery);search.setSingleLine(true);search.setPadding(dp(4),dp(8),dp(4),dp(12));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){drawerQuery=s.toString();lastThreadList="";renderThreads();}public void afterTextChanged(Editable e){}});
        showDialog(new TangoSheet.Builder(this).setTitle("Search chats").setView(search).setPositiveButton("Done",null).setNeutralButton("Clear",(d,w)->{drawerQuery="";lastThreadList="";renderThreads();}));pendingSheetKind="search";sheetEditor=search;
    }
    private void selectChat(String id) {
        if(drawerVisible){navigateFromDrawer(() -> selectChat(id));return;}
        rememberDraft();cancelScheduled();stopListening();
        try {if(archivedThreads)AgentService.archiveThread(this,id,false);if(AgentService.selectThread(this,id)){renderedThread="";showHome();}}catch(Exception e){errorDialog(e.getMessage());}
    }
    private void threadOptions(JSONObject thread) {
        String id=thread.optString("id");boolean archived=thread.optBoolean("archived");
        showDialog(new TangoSheet.Builder(this).setTitle(thread.optString("title")).setItems(new String[]{"Rename",archived?"Restore":"Archive","Delete"},(d,choice)->{
            if(choice==0)showRenameThread(id,thread.optString("title"));
            else if(choice==1){try{AgentService.archiveThread(this,id,!archived);lastThreadList="";renderThreads();renderTask();}catch(Exception e){errorDialog(e.getMessage());}}
            else showDialog(new TangoSheet.Builder(this).setTitle("Delete chat?").setMessage("This removes this chat and its saved history from your phone.").setDestructiveButton("Delete",(dialog,w)->{try{AgentService.deleteThread(this,id);lastThreadList="";renderThreads();renderTask();}catch(Exception e){errorDialog(e.getMessage());}}).setNegativeButton("Cancel",null));
        }));
    }
    private void showRenameThread(String id,String draft) {
        EditText name=chatInput("Chat name",draft);name.setSingleLine(true);name.setPadding(dp(4),dp(8),dp(4),dp(12));
        showDialog(new TangoSheet.Builder(this).setTitle("Rename chat").setView(name).setPositiveButton("Save",(dialog,w)->{try{AgentService.renameThread(this,id,name.getText().toString());lastThreadList="";lastRendered="";renderThreads();renderTask();toast("Chat renamed");}catch(Exception e){errorDialog(e.getMessage());}}).setNegativeButton("Cancel",null));pendingSheetKind="rename";pendingSheetThread=id;sheetEditor=name;
    }
    private void showCodexConnection() {
        hideKeyboard();JSONObject info=CodexConnection.info(this);boolean connected=info.optBoolean("connected");
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        TextView state=text(connected?"Connected to this phone":"Test your phone with Codex",21,WHITE,true);content.addView(state);spaceIn(content,10);content.addView(text(connected?"Codex can send prompts and inspect apps over USB. You can disconnect at any time.":"Connect your phone to your computer over USB, with USB debugging enabled. Codex can then send prompts and inspect apps through Tango.",16,MUTED,false));
        if(connected){spaceIn(content,20);Button details=button("Connection details",false);details.setOnClickListener(v -> {dismissSheetsImmediately();showConnectionDetails();});content.addView(details,new LinearLayout.LayoutParams(-1,dp(52)));}
        TangoSheet.Builder sheet=new TangoSheet.Builder(this).setTitle("Connect to Codex").setView(content).setNegativeButton("Close",null);
        if(connected){sheet.setPositiveButton("Copy setup",(d,w)->copyCodexSetup());sheet.setNeutralButton("Disconnect",(d,w)->{CodexConnection.disconnect(this);toast("Codex disconnected");});}
        else sheet.setPositiveButton("Connect",(d,w)->{try{CodexConnection.connect(this);showCodexConnection();}catch(Exception e){errorDialog(e.getMessage());}});
        showDialog(sheet);pendingSheetKind="connection";
    }
    private void showConnectionDetails() {
        JSONObject info=CodexConnection.info(this);if(!info.optBoolean("connected")){showCodexConnection();return;}
        showDialog(new TangoSheet.Builder(this).setTitle("Connection details").setMessage(info.optString("adbForwardCommand")+"\n\nConnection token\n"+info.optString("token")).setPositiveButton("Copy setup",(d,w)->copyCodexSetup()).setNegativeButton("Close",null));
    }
    private void copyCodexSetup() {
        JSONObject info=CodexConnection.info(this);String setup=info.optString("adbForwardCommand")+"\n\n# Replace the path below with Tango's folder on your computer.\ncodex mcp add tango --env TANGO_CONNECTION_TOKEN="+info.optString("token")+" -- node \"/absolute/path/to/Tango/scripts/codex-tango.mjs\" mcp";
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tango Codex connection",setup));toast("Codex setup copied.");
    }
    private void hideKeyboard(){if(command!=null)command.clearFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    /** A connected Codex prompt has the same foreground handoff as tapping Send. */
    public static void prepareForCodexThreadChange() {
        MainActivity activity=foreground.get();
        if(activity!=null&&activity.resumed&&activity.alive()&&CodexConnection.info(activity).optBoolean("connected"))activity.rememberDraft();
    }
    public static void handoffToCodex() {
        MainActivity activity=foreground.get();
        if(activity==null||!activity.resumed||!activity.alive()||!CodexConnection.info(activity).optBoolean("connected"))return;
        activity.rememberDraft();activity.cancelScheduled();activity.releaseInput();activity.hideKeyboard();activity.moveTaskToBack(true);
    }
    private void showTaskDetails() {
        StringBuilder content=new StringBuilder(AgentService.status).append("\n\n");
        if(!AgentService.goal.isBlank())content.append(AgentService.goal).append("\n\n");
        JSONArray entries=AgentService.journalSnapshot();
        for(int i=Math.max(0,entries.length()-40);i<entries.length();i++) {JSONObject entry=entries.optJSONObject(i);if(entry!=null)content.append(entry.optString("who")).append(" · ").append(entry.optString("text")).append("\n\n");}
        if(content.length()==0)content.append(AgentService.status);
        ScrollView scroll=new ScrollView(this); TextView details=text(content.toString().trim(),14,WHITE,false); details.setTextIsSelectable(true); details.setPadding(dp(24),dp(12),dp(24),dp(20)); details.setLineSpacing(dp(4),1); scroll.addView(details);
        showDialog(new TangoSheet.Builder(this).setTitle("Task details").setView(scroll).setPositiveButton("Close",null));
    }
    private void permissionDisclosure() {
        showDialog(new TangoSheet.Builder(this).setTitle("Allow Tango to operate your phone?")
            .setMessage("Tango can read visible screen text, capture screenshots, and tap, type, or swipe in other apps during your tasks. Screen content is sent to the AI connection you choose. Password screens pause the agent. You can stop from its floating control or notification.\n\nIn the next screen, open Installed apps → Tango phone control and enable it.")
            .setPositiveButton("Open Accessibility settings",(d,w) -> {if(AgentService.instance!=null&&AgentService.state.equals("running"))AgentService.instance.pauseTask("Finish phone control setup, then continue.");startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}).setNegativeButton("Cancel",null));
    }
    private void listen() {
        shouldListen=false;
        if(listening) { updateMicrophone(); return; }
        if(!auth.hasPlanAccess()) { shouldListen=false; showSignIn(); return; }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { shouldListen=true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); return; }
        if(!SpeechRecognizer.isRecognitionAvailable(this)) { voiceStatus("Speech is unavailable. Type a command."); return; }
        int voiceTicket=++voiceGeneration;
        boolean onDevice=!forceSystemRecognition && Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        // Change the visible control in the tap's frame, before binding the speech service.
        microphoneIcon(true);voiceStatus("Listening…");
        try {
        if(recognizer!=null)recognizer.destroy();
        if(onDevice) recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        else recognizer=SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { if(resumed&&voiceTicket==voiceGeneration&&!voiceFinishing){microphoneIcon(true);voiceStatus("Listening…");} }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] bytes) {}
            @Override public void onEndOfSpeech() { if(resumed&&voiceTicket==voiceGeneration){voiceFinishing=true;updateMicrophone();voiceStatus("Understanding…");} }
            @Override public void onError(int code) {
                if(voiceTicket!=voiceGeneration)return;
                int fallbackTicket=++voiceGeneration;
                if(onDevice && (code==12 || code==13) && !voiceFinishing) {
                    forceSystemRecognition=true;microphoneIcon(true);voiceStatus("Using your device's speech service…");
                    handler.post(() -> {
                        if(fallbackTicket!=voiceGeneration)return;
                        if(resumed&&alive()&&!settingsVisible&&!authVisible&&!voiceFinishing){microphoneIcon(false);listen();}
                        else stopListening();
                    });return;
                }
                microphoneIcon(false); voiceStatus(speechError(code));
            }
            @Override public void onResults(Bundle b) {
                if(!resumed||voiceTicket!=voiceGeneration)return;
                voiceGeneration++;
                microphoneIcon(false);ArrayList<String> results=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(results==null || results.isEmpty()) {voiceStatus("I did not catch that. Try again.");return;}
                command.setText(results.get(0)); command.clearFocus();
                if(prefs.getBoolean("auto_run_voice",true)) {
                    voiceStatus("Starting in 1 second…");
                    scheduledRun=() -> { scheduledRun=null; runCommand(command.getText().toString()); }; handler.postDelayed(scheduledRun,1000);
                } else voiceStatus("Ready. Tap send.");
            }
            @Override public void onPartialResults(Bundle b) { if(!resumed||voiceTicket!=voiceGeneration)return; ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if(r!=null&&!r.isEmpty())command.setText(r.get(0)); }
            @Override public void onEvent(int type,Bundle b) {}
        });
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.US.toLanguageTag()).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1).putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,2000L);
        recognizer.startListening(i);
        } catch(RuntimeException error) {
            if(voiceTicket==voiceGeneration){voiceGeneration++;microphoneIcon(false);voiceStatus("Speech could not start. Try again.");}
        }
    }
    private void resumePendingVoice() {
        if(!shouldListen||!resumed||!alive()||settingsVisible||authVisible||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
        int permissionTicket=voiceGeneration;
        handler.post(() -> {if(shouldListen&&permissionTicket==voiceGeneration&&resumed&&alive()&&!settingsVisible&&!authVisible)listen();});
    }
    private String speechError(int code) {
        return switch(code) {
            case SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Try again.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to speak.";
            case SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech could not connect. Type a command.";
            default -> "Speech recognition stopped ("+code+"). Try again.";
        };
    }
    /** Finish this recording once, keeping its final transcript callback alive. */
    private void finishListening() {
        if(!listening||voiceFinishing)return;
        voiceFinishing=true;updateMicrophone();voiceStatus("Understanding…");
        try {if(recognizer!=null)recognizer.stopListening();}
        catch(RuntimeException error){stopListening();voiceStatus("Speech recognition stopped. Try again.");}
    }
    /** Cancel invalidates both recognizer callbacks and a pending on-device fallback. */
    private void stopListening() {
        shouldListen=false;voiceGeneration++;microphoneIcon(false);
        try {if(recognizer!=null)recognizer.cancel();}catch(RuntimeException ignored){}
    }
    private void cancelScheduled() { if(scheduledRun!=null) {handler.removeCallbacks(scheduledRun);scheduledRun=null;if(statusView!=null)voiceStatus("Edited. Tap send.");syncUserInteraction();} }
    private void runCommand(String text) {
        if(!auth.hasPlanAccess()) { showSignIn(); return; }
        if(importingAttachments){voiceStatus("Adding your files…");return;}
        if(text.isBlank()&&AgentService.threadAttachments(this).length()==0) { voiceStatus("Say or type a task first."); return; }
        if(PhoneAccessibilityService.instance==null) { permissionDisclosure(); return; }
        releaseInput();
        if(AgentService.state.equals("running")) sendService("steer",text);
        else if(AgentService.state.equals("waiting")||AgentService.state.equals("paused"))sendService("resume",text);
        else { Intent intent=new Intent(this,AgentService.class).setAction("start").putExtra("goal",text).putExtra("threadId",renderedThread); startForegroundService(intent); }
        command.setText("");reply.setText("");draftCommand="";draftReply="";rememberDraft();
        command.clearFocus(); ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(command.getWindowToken(),0);
        moveTaskToBack(true);
    }
    private void continueTask() {
        if(!auth.hasPlanAccess()) { showSignIn(); return; }
        if(importingAttachments){voiceStatus("Adding your files…");return;}
        String answer=command.getText().toString();if(answer.isBlank())answer=reply.getText().toString();if(AgentService.state.equals("running")&&answer.isBlank()&&AgentService.threadAttachments(this).length()==0){command.requestFocus();toast("Add an instruction to steer the task.");return;}
        releaseInput();sendService(AgentService.state.equals("running")?"steer":"resume",answer);reply.setText("");command.setText("");rememberDraft();moveTaskToBack(true);
    }
    private void sendService(String action,String answer) {
        if(!action.equals("pause")&&!action.equals("stop")&&!auth.hasPlanAccess()) { showSignIn(); return; }
        if((action.equals("pause")||action.equals("stop")) && AgentService.instance==null) { AgentService.state="stopped"; AgentService.status="Stopped."; renderTask(); return; }
        startForegroundService(new Intent(this,AgentService.class).setAction(action).putExtra("reply",answer).putExtra("threadId",renderedThread));
    }
    private void showSignIn() {
        dismissSheetsImmediately();dismissComposerOverlay(false);closeDrawer(false);renderedThread="";cancelScheduled();stopListening();shouldListen=false;authVisible=true;settingsVisible=false;AgentService.setUserInteracting(false);
        if(AgentService.instance!=null&&AgentService.state.equals("running"))AgentService.instance.pauseTask("Sign in with ChatGPT to continue.");
        shell(); container.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=text("Tango",24,WHITE,true); mark.setGravity(Gravity.CENTER); container.addView(mark); space(32);
        Button signin=chatGptButton(); signin.setEnabled(!signingIn);
        signin.setOnClickListener(v -> beginSignIn(auth.suggestedSignInClientId(),auth.isSignedIn()&&!auth.hasPlanAccess())); container.addView(signin,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView plan=text("Eligible requests use your ChatGPT plan (preview).",12,MUTED,false); plan.setGravity(Gravity.CENTER); space(12);container.addView(plan);
        String pendingStatus=auth.status();
        signInStatus=text(signingIn?(pendingStatus.isBlank()?"Finish sign-in in your browser.":pendingStatus):authMessage,13,MUTED,false); signInStatus.setGravity(Gravity.CENTER); signInStatus.setPadding(0,dp(16),0,dp(4)); signInStatus.setVisibility(signInStatus.getText().length()==0?View.GONE:View.VISIBLE); container.addView(signInStatus);
        if(signingIn) { Button cancel=button("Cancel",false); cancel.setOnClickListener(v -> {auth.cancelSignIn();signingIn=false;authMessage="Sign-in canceled.";showSignIn();}); space(12);container.addView(cancel,new LinearLayout.LayoutParams(-1,dp(48))); }
        else if(!auth.diagnosticText().isBlank()) {
            Button details=button("Copy sign-in details",false); details.setTextSize(12);
            details.setOnClickListener(v -> { ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE); clipboard.setPrimaryClip(ClipData.newPlainText("Tango sign-in details",auth.diagnosticText())); toast("Details copied."); });
            space(12); container.addView(details,new LinearLayout.LayoutParams(-1,dp(48)));
        }
        if(auth.accounts().length()>0) {Button accounts=button("Saved accounts",false);accounts.setEnabled(!signingIn);accounts.setOnClickListener(v -> chooseAccount());space(12);container.addView(accounts,new LinearLayout.LayoutParams(-1,dp(48)));}
        Button usage=button("Usage",false); usage.setTextColor(MUTED);usage.setBackgroundColor(BG);usage.setOnClickListener(v -> openWeb("https://chatgpt.com/settings/usage"));space(12);container.addView(usage,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private void beginSignIn(String clientId,boolean consent) {
        cancelScheduled(); stopListening();
        if(AgentService.instance!=null)AgentService.instance.pauseTask("Paused while you connect a ChatGPT account.");
        signingIn=true; authMessage="Opening OpenAI sign-in…"; showSignIn();
        auth.beginSignIn(this,clientId==null?"":clientId,consent,signInListener);
    }
    private void chooseAccount() {
        JSONArray saved=auth.accounts(); if(saved.length()==0) {beginSignIn("",false);return;}
        String[] names=new String[saved.length()]; for(int i=0;i<saved.length();i++) {JSONObject a=saved.optJSONObject(i);names[i]=a.optString("label",a.optString("email","ChatGPT account"))+(a.optBoolean("active")?" • current":"");}
        showDialog(new TangoSheet.Builder(this).setTitle("Accounts").setItems(names,(d,index) -> {
            JSONObject selected=saved.optJSONObject(index); if(selected==null)return;
            rememberDraft();CodexConnection.disconnect(this);renderedThread="";draftCommand="";draftReply="";
            if(AgentService.instance!=null)AgentService.instance.stopTask();
            String id=selected.optString("client_id"); try {auth.switchAccount(id);}catch(Exception e){errorDialog(e.getMessage());return;}
            if(auth.hasPlanAccess()) {authMessage="";showHome();} else beginSignIn(id,auth.isSignedIn());
        }).setNeutralButton("Add account",(d,w) -> beginSignIn("",false)).setNegativeButton("Cancel",null));
    }
    private void showSettings() {
        if(drawerVisible){navigateFromDrawer(this::showSettings);return;}
        if(!auth.hasPlanAccess()){showSignIn();return;}
        rememberDraft();dismissSheetsImmediately();dismissComposerOverlay(false);closeDrawer(false);hideKeyboard();cancelScheduled();stopListening();authVisible=false;settingsVisible=true;syncUserInteraction();shell();
        LinearLayout header=row();header.addView(text("Account",26,WHITE,true),new LinearLayout.LayoutParams(0,-2,1));Button back=roundIcon(R.drawable.ic_close,false,"Back");back.setOnClickListener(v -> showHome());header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));container.addView(header);
        space(26);LinearLayout identity=card();identity.setBackground(background(0xFF1B1B1B,24));identity.setPadding(dp(20),dp(20),dp(20),dp(20));TextView email=text(auth.email().isBlank()?"ChatGPT account":auth.email(),18,WHITE,true);email.setMaxLines(2);identity.addView(email);spaceIn(identity,6);identity.addView(text("ChatGPT plan",14,MUTED,false));spaceIn(identity,16);Button accounts=button("Manage accounts",false);accounts.setBackground(background(0xFF303030,18));accounts.setOnClickListener(v -> chooseAccount());identity.addView(accounts,new LinearLayout.LayoutParams(-1,dp(50)));container.addView(identity);
        space(22);accountMode=accountRow("Model mode · "+ApiClient.modeName(new ApiClient(this).selectedMode()),"Choose how much time Tango spends reasoning",v -> showModelModes());
        accountRow("Usage","Your ChatGPT plan and limits",v -> openWeb("https://chatgpt.com/settings/usage"));
        accountRow("Connect to Codex",CodexConnection.info(this).optBoolean("connected")?"Connected · Manage this connection":"Use Tango from your computer",v -> showCodexConnection());
        space(12);addPreferencesDisclosure();space(10);addAdvancedDisclosure();
        space(24);Button signout=button("Sign out",false);signout.setTextColor(0xFFEDAEAE);signout.setBackgroundColor(Color.TRANSPARENT);signout.setOnClickListener(v -> showDialog(new TangoSheet.Builder(this).setTitle("Sign out?").setMessage("Your chats stay saved on this phone. Active phone control and the Codex connection will stop.").setDestructiveButton("Sign out",(d,w)->performSignOut()).setNegativeButton("Cancel",null)));container.addView(signout,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    private Button accountRow(String title,String subtitle,View.OnClickListener action) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(3),0,dp(12));
        Button button=button(title,false);button.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);button.setTextSize(17);button.setPadding(dp(4),0,dp(12),0);button.setBackgroundColor(Color.TRANSPARENT);button.setOnClickListener(action);row.addView(button,new LinearLayout.LayoutParams(-1,dp(48)));
        if(!subtitle.isBlank()){TextView detail=text(subtitle,13,MUTED,false);detail.setPadding(dp(4),0,dp(12),0);row.addView(detail);}
        container.addView(row);return button;
    }
    private void addPreferencesDisclosure() {
        LinearLayout section=disclosureCard();Button toggle=disclosureButton("Voice & shortcuts",preferencesExpanded);section.addView(toggle,new LinearLayout.LayoutParams(-1,dp(56)));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),0,dp(14),dp(14));body.setVisibility(preferencesExpanded?View.VISIBLE:View.GONE);section.addView(body);
        preference(body,"Listen on open","Start listening when you open Tango","listen_on_launch",true);
        preference(body,"Run voice commands","Send after a one-second edit window","auto_run_voice",true);
        preference(body,"Volume shortcut","Press volume down twice to open Tango","volume_shortcut",true);
        Button setup=button("Phone setup",false);setup.setBackground(background(0xFF303030,18));setup.setOnClickListener(v -> showSetup());spaceIn(body,10);body.addView(setup,new LinearLayout.LayoutParams(-1,dp(50)));
        toggle.setOnClickListener(v -> {preferencesExpanded=!preferencesExpanded;toggle.setCompoundDrawablesWithIntrinsicBounds(0,0,preferencesExpanded?R.drawable.ic_chevron_up:R.drawable.ic_chevron_down,0);toggle.setStateDescription(preferencesExpanded?"Expanded":"Collapsed");revealDisclosure(body,preferencesExpanded);UiMotion.tick(v);});container.addView(section);
    }
    private void addAdvancedDisclosure() {
        LinearLayout section=disclosureCard();Button toggle=disclosureButton("Advanced",advancedExpanded);section.addView(toggle,new LinearLayout.LayoutParams(-1,dp(56)));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(14),0,dp(14),dp(14));body.setVisibility(advancedExpanded?View.VISIBLE:View.GONE);section.addView(body);
        Button model=button("Model · "+new ApiClient(this).selectedModelName(),false);model.setMaxLines(2);model.setBackground(background(0xFF303030,18));model.setOnClickListener(v -> showModelCatalog(model));body.addView(model,new LinearLayout.LayoutParams(-1,dp(56)));spaceIn(body,10);
        preference(body,"Fast mode I","Reuse verified steps to reduce waiting","fast_mode",false);
        Button diagnostics=button("Copy AI details",false);diagnostics.setBackgroundColor(Color.TRANSPARENT);diagnostics.setOnClickListener(v -> copyAiDetails());body.addView(diagnostics,new LinearLayout.LayoutParams(-1,dp(50)));
        Button forget=button("Clear task history",false);forget.setTextColor(0xFFEDAEAE);forget.setBackgroundColor(Color.TRANSPARENT);forget.setOnClickListener(v -> showDialog(new TangoSheet.Builder(this).setTitle("Clear task history?").setMessage("Removes saved chats, attachments, commands and results from this phone. This cannot be undone.").setDestructiveButton("Clear",(d,w)->{try{AgentService.clearThreads(this);renderedThread="";draftCommand="";draftReply="";toast("Task history cleared");}catch(Exception e){errorDialog(e.getMessage());}}).setNegativeButton("Cancel",null)));body.addView(forget,new LinearLayout.LayoutParams(-1,dp(50)));
        toggle.setOnClickListener(v -> {advancedExpanded=!advancedExpanded;toggle.setCompoundDrawablesWithIntrinsicBounds(0,0,advancedExpanded?R.drawable.ic_chevron_up:R.drawable.ic_chevron_down,0);toggle.setStateDescription(advancedExpanded?"Expanded":"Collapsed");revealDisclosure(body,advancedExpanded);UiMotion.tick(v);});container.addView(section);
    }
    private LinearLayout disclosureCard(){LinearLayout section=new LinearLayout(this);section.setOrientation(LinearLayout.VERTICAL);section.setBackground(background(0xFF1B1B1B,22));android.animation.LayoutTransition transition=new android.animation.LayoutTransition();transition.setDuration(UiMotion.duration(UiMotion.ENTER));transition.setAnimateParentHierarchy(false);section.setLayoutTransition(transition);return section;}
    private Button disclosureButton(String title,boolean open){Button toggle=button(title,false);toggle.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);toggle.setTextSize(17);toggle.setBackgroundColor(Color.TRANSPARENT);toggle.setCompoundDrawablesWithIntrinsicBounds(0,0,open?R.drawable.ic_chevron_up:R.drawable.ic_chevron_down,0);toggle.setStateDescription(open?"Expanded":"Collapsed");return toggle;}
    private void revealDisclosure(View body,boolean open){body.animate().cancel();body.setAlpha(1);body.setTranslationY(0);body.setVisibility(open?View.VISIBLE:View.GONE);if(open)UiMotion.reveal(body,0);}
    private void preference(LinearLayout target,String title,String detail,String key,boolean initial){
        LinearLayout line=row();line.setPadding(0,dp(10),0,dp(10));LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.addView(text(title,16,WHITE,false));TextView description=text(detail,12,MUTED,false);description.setPadding(0,dp(5),dp(8),0);labels.addView(description);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);toggle.setContentDescription(title);toggle.setChecked(prefs.getBoolean(key,initial));toggle.setMinWidth(dp(48));toggle.setMinHeight(dp(48));toggle.setOnCheckedChangeListener((button,on)->{prefs.edit().putBoolean(key,on).apply();UiMotion.tick(button);});line.addView(toggle,new LinearLayout.LayoutParams(dp(52),dp(48)));UiMotion.attachPress(toggle);target.addView(line,new LinearLayout.LayoutParams(-1,-2));
    }
    private void showModelCatalog(Button model) {
        String account=auth.activeClientId();ApiClient models=new ApiClient(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);LinearLayout waiting=row();android.widget.ProgressBar spinner=new android.widget.ProgressBar(this);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(WHITE));waiting.addView(spinner,new LinearLayout.LayoutParams(dp(24),dp(24)));TextView caption=text("Loading available models…",16,MUTED,false);caption.setPadding(dp(14),dp(12),0,dp(12));waiting.addView(caption);content.addView(waiting);
        TangoSheet sheet=showDialog(new TangoSheet.Builder(this).setTitle("Model").setView(content).setNegativeButton("Cancel",null));
        network.execute(() -> {try{JSONArray catalog=models.listModels();handler.post(() -> {if(!alive()||!sheet.isShowing()||!account.equals(auth.activeClientId()))return;content.removeAllViews();for(int i=0;i<catalog.length();i++){JSONObject entry=catalog.optJSONObject(i);if(entry==null)continue;String slug=entry.optString("slug");Button choice=button(entry.optString("display_name",slug),false);choice.setMaxLines(2);choice.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);choice.setBackgroundColor(Color.TRANSPARENT);choice.setOnClickListener(v -> {try{models.selectModel(slug);model.setText("Model · "+models.selectedModelName());UiMotion.confirm(v);sheet.dismiss();toast("Model updated");}catch(Exception e){errorDialog(e.getMessage());}});content.addView(choice,new LinearLayout.LayoutParams(-1,dp(58)));}UiMotion.reveal(content,0);});}catch(Exception error){handler.post(() -> {if(!alive()||!sheet.isShowing()||!account.equals(auth.activeClientId()))return;content.removeAllViews();content.addView(text("Models couldn’t load. Check your connection and try again.",16,MUTED,false));Button retry=button("Try again",false);retry.setOnClickListener(v -> {sheet.dismissImmediately();showModelCatalog(model);});content.addView(retry,new LinearLayout.LayoutParams(-1,dp(52)));});}});
    }
    private void performSignOut(){
        rememberDraft();CodexConnection.disconnect(this);renderedThread="";draftCommand="";draftReply="";
        if(AgentService.instance!=null)AgentService.instance.stopTask();auth.signOut(new ChatGptAuth.Listener(){@Override public void onStatus(String message){if(alive())toast(message);}@Override public void onSuccess(){if(!alive())return;authMessage="";showEntry();}@Override public void onError(String message){if(!alive())return;authMessage=message;if(auth.hasPlanAccess()){showSettings();errorDialog(message);}else showSignIn();}});authMessage="Signing out…";showSignIn();
    }
    private void showSetup() {
        showDialog(new TangoSheet.Builder(this).setTitle("Setup").setMessage("S23: Settings → Advanced features → Side button → Double press → Open app → Tango.\n\nUnlock your phone before a task. If Tango sleeps in the background, add it to Never sleeping apps.\n\nVoice commands start after a 1-second edit window. The volume shortcut uses two presses of volume down.")
            .setPositiveButton("Phone control",(d,w) -> permissionDisclosure()).setNeutralButton("Assistant",(d,w) -> {if(AgentService.instance!=null&&AgentService.state.equals("running"))AgentService.instance.pauseTask("Finish assistant setup, then continue.");startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));}).setNegativeButton("Close",null));
    }
    private void openWeb(String url) {
        Uri uri=Uri.parse(url); String host=uri.getHost(); if(!"https".equals(uri.getScheme())||!("chatgpt.com".equals(host)||"auth.openai.com".equals(host))) {errorDialog("Unexpected account address.");return;}
        try {startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(android.content.ActivityNotFoundException e){errorDialog("Install or enable a browser to continue.");}
    }
    private void probe() {
        if(!auth.hasPlanAccess())showSignIn();
    }
    private void copyAiDetails() {
        String details=new ApiClient(this).diagnosticText();
        if(details.isBlank()) {toast("No AI error details.");return;}
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Tango AI connection details",details));
        toast("Details copied.");
    }
    private void setting(String title,String key,boolean initial) {
        Switch toggle=new Switch(this);toggle.setText(title);toggle.setTextColor(WHITE);toggle.setTextSize(14);toggle.setPadding(0,dp(6),0,dp(6));toggle.setChecked(prefs.getBoolean(key,initial));toggle.setOnCheckedChangeListener((b,on) -> prefs.edit().putBoolean(key,on).apply());container.addView(toggle,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private EditText field(String value,String hint,boolean secret) { EditText e=new EditText(this); e.setText(value); e.setHint(hint); e.setTextColor(WHITE); e.setHintTextColor(MUTED); e.setTextSize(14); e.setSingleLine(true); e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); return e; }
    private LinearLayout row() { LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private LinearLayout card() { LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(16),dp(16),dp(16)); box.setBackground(background(CARD,8)); return box; }
    private GradientDrawable background(int color,int radius) { GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius));if(color!=WHITE&&color!=Color.TRANSPARENT)g.setStroke(dp(1),BORDER);return g; }
    private TextView text(String value,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL)); return t; }
    private Button chatGptButton() { Button b=button("Continue with ChatGPT",true); b.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_chatgpt,0,0,0); b.setCompoundDrawablePadding(dp(10)); return b; }
    private Button button(String title,boolean primary) { return styleButton(new Button(this),title,primary); }
    private Button styleButton(Button b,String title,boolean primary) { b.setText(title); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(primary?BG:WHITE); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); b.setBackgroundTintList(null);b.setBackground(background(primary?WHITE:BG,14));b.setElevation(0);b.setMinHeight(dp(48));b.setMinimumWidth(0);b.setMinWidth(0);b.setPadding(dp(14),dp(8),dp(14),dp(8));UiMotion.attachPress(b);return b; }
    private Button iconButton(int resource,boolean primary,String label) {
        IconButton b=new IconButton(resource,primary);styleButton(b,"",primary);b.setContentDescription(label);b.setTooltipText(label);return b;
    }
    private Button roundIcon(int resource,boolean primary,String label) {Button b=iconButton(resource,primary,label);b.setPadding(0,0,0,0);b.setMinHeight(0);b.setMinimumHeight(0);b.setBackground(background(primary?WHITE:0xFF282828,40));return b;}
    private void microphoneIcon(boolean recording) {
        listening=recording;voiceFinishing=false;updateMicrophone();syncUserInteraction();
    }
    private void updateMicrophone() {
        if(mic instanceof IconButton) ((IconButton)mic).setIcon(listening&&!voiceFinishing?R.drawable.ic_stop:R.drawable.ic_mic);
        if(mic!=null){mic.setEnabled(!voiceFinishing);mic.setContentDescription(voiceFinishing?"Transcribing voice command":listening?"Finish voice command":"Speak a command");mic.setTooltipText(mic.getContentDescription());}
    }
    private final class IconButton extends Button {
        private android.graphics.drawable.Drawable icon;
        private android.graphics.drawable.Drawable previousIcon;
        private final boolean primary;
        private int iconResource;
        private float iconProgress=1f;
        private ValueAnimator iconAnimation;
        IconButton(int resource,boolean primary){super(MainActivity.this);this.primary=primary;setIcon(resource);}
        void setIcon(int resource){
            if(iconResource==resource)return;
            if(iconAnimation!=null){iconAnimation.cancel();iconAnimation=null;}
            previousIcon=icon;iconResource=resource;icon=getDrawable(resource).mutate();icon.setTint(primary?BG:WHITE);
            iconProgress=1f;
            if(previousIcon!=null&&isAttachedToWindow()&&UiMotion.enabled()){
                iconProgress=0f;iconAnimation=ValueAnimator.ofFloat(0,1);iconAnimation.setDuration(UiMotion.STATE);iconAnimation.setInterpolator(UiMotion.EASE);
                iconAnimation.addUpdateListener(a -> {iconProgress=(Float)a.getAnimatedValue();invalidate();});
                iconAnimation.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(iconAnimation==a){previousIcon=null;iconAnimation=null;iconProgress=1f;invalidate();}}});iconAnimation.start();
            }else previousIcon=null;
            invalidate();
        }
        @Override protected void onDraw(android.graphics.Canvas canvas){
            super.onDraw(canvas);int size=dp(24),left=getText().length()>0?getWidth()-size-dp(4):(getWidth()-size)/2,top=(getHeight()-size)/2;
            int alpha=isEnabled()?255:100;
            if(previousIcon!=null&&iconProgress<1f){previousIcon.setBounds(left,top,left+size,top+size);previousIcon.setAlpha((int)(alpha*(1-iconProgress)));canvas.save();float scale=1-.12f*iconProgress;canvas.scale(scale,scale,left+size/2f,top+size/2f);previousIcon.draw(canvas);canvas.restore();}
            icon.setBounds(left,top,left+size,top+size);icon.setAlpha((int)(alpha*iconProgress));canvas.save();float scale=.88f+.12f*iconProgress;canvas.scale(scale,scale,left+size/2f,top+size/2f);icon.draw(canvas);canvas.restore();
        }
        @Override protected void onDetachedFromWindow(){if(iconAnimation!=null){iconAnimation.cancel();iconAnimation=null;}previousIcon=null;iconProgress=1f;super.onDetachedFromWindow();}
    }
    private void space(int h) { spaceIn(container,h); }
    private void spaceIn(LinearLayout target,int h) { target.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h))); }
    private void spaceHorizontal(LinearLayout target,int w) { target.addView(new View(this),new LinearLayout.LayoutParams(dp(w),1)); }
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }
    private boolean alive() { return !isFinishing()&&!isDestroyed(); }
    private void rememberDraft() {if(settingsVisible||authVisible||renderedThread.isBlank()||!renderedThread.equals(AgentService.activeThreadId(this)))return;if(command!=null)draftCommand=command.getText().toString();if(reply!=null)draftReply=reply.getText().toString();try{AgentService.stageThreadDraft(this,auth.activeClientId(),renderedThread,draftCommand,draftReply);}catch(Exception e){toast("Could not save this chat's draft.");}}
    private void syncUserInteraction() {AgentService.setUserInteracting(resumed&&!authVisible);}
    private void releaseInput() {if(command!=null)command.clearFocus();if(reply!=null)reply.clearFocus();if(container!=null)container.requestFocus();stopListening();AgentService.setUserInteracting(false);}
    private void voiceStatus(String message) {if(statusView!=null){UiMotion.crossfade(statusView,message);statusView.setVisibility(message.isBlank()?View.GONE:View.VISIBLE);}}
    private TangoSheet showDialog(TangoSheet.Builder builder) {
        dismissComposerOverlay(false);dismissSheetsImmediately();interactionDialogs++;syncUserInteraction();TangoSheet sheet=builder.create();activeSheets.add(sheet);sheet.setOnDismissListener(d -> {activeSheets.remove(sheet);interactionDialogs=activeSheets.size();if(activeSheets.isEmpty()){pendingSheetKind="";pendingSheetThread="";pendingSheetDraft="";sheetEditor=null;}syncUserInteraction();});sheet.show();return sheet;
    }
    private void pauseSheets(){for(TangoSheet sheet:new ArrayList<>(activeSheets))sheet.onHostPaused();if(transientNotice!=null)removeNotice(false);}
    private void resumeSheets(){for(TangoSheet sheet:new ArrayList<>(activeSheets))sheet.onHostResumed();}
    private void dismissSheetsImmediately(){for(TangoSheet sheet:new ArrayList<>(activeSheets))sheet.dismissImmediately();activeSheets.clear();interactionDialogs=0;pendingSheetKind="";pendingSheetThread="";pendingSheetDraft="";sheetEditor=null;}
    private void saveUiState(Bundle out){out.putString("uiAccount",auth.activeClientId());out.putString("scrollThread",renderedThread);out.putInt("conversationScroll",conversation==null?0:conversation.getScrollY());out.putInt("accountScroll",currentScreen instanceof ScrollView?currentScreen.getScrollY():0);out.putString("uiScreen",settingsVisible?"account":"chat");out.putInt("composerSelection",command==null?0:command.getSelectionStart());out.putInt("sheetSelection",sheetEditor==null?0:sheetEditor.getSelectionStart());out.putBoolean("advancedExpanded",advancedExpanded);out.putBoolean("preferencesExpanded",preferencesExpanded);out.putBoolean("drawerOpen",drawerVisible);out.putString("drawerQuery",drawerQuery);out.putBoolean("archivedThreads",archivedThreads);out.putString("sheetKind",pendingSheetKind);out.putString("sheetThread",pendingSheetThread);out.putString("sheetDraft",sheetEditor==null?pendingSheetDraft:sheetEditor.getText().toString());}
    private void restoreUiState(Bundle saved){if(saved==null||!auth.hasPlanAccess()||!auth.activeClientId().equals(saved.getString("uiAccount",auth.activeClientId())))return;advancedExpanded=saved.getBoolean("advancedExpanded");preferencesExpanded=saved.getBoolean("preferencesExpanded");if("account".equals(saved.getString("uiScreen")))showSettings();else if(saved.getBoolean("drawerOpen")){openDrawer(false);drawerQuery=saved.getString("drawerQuery","");archivedThreads=saved.getBoolean("archivedThreads");lastThreadList="";renderThreads();setDrawerProgress(1);}String kind=saved.getString("sheetKind","");if(kind.equals("search")){drawerQuery=saved.getString("sheetDraft","");showThreadSearch();}else if(kind.equals("rename")){String id=saved.getString("sheetThread","");try{if(AgentService.getThread(this,id)!=null)showRenameThread(id,saved.getString("sheetDraft",""));}catch(Exception ignored){}}else if(kind.equals("connection"))showCodexConnection();if(command!=null&&!settingsVisible)command.setSelection(Math.max(0,Math.min(command.length(),saved.getInt("composerSelection",command.length()))));if(sheetEditor!=null)sheetEditor.setSelection(Math.max(0,Math.min(sheetEditor.length(),saved.getInt("sheetSelection",sheetEditor.length()))));if(settingsVisible)restoreScrollAfterLayout(currentScreen,saved.getInt("accountScroll"));else if(renderedThread.equals(saved.getString("scrollThread",renderedThread)))restoreScrollAfterLayout(conversation,saved.getInt("conversationScroll"));}
    private void restoreScrollAfterLayout(View view,int scrollY){if(view==null)return;view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(this);view.post(() -> {if(alive()&&view.isAttachedToWindow())view.scrollTo(0,Math.max(0,scrollY));});return true;}});view.requestLayout();}
    private void toast(String value) {
        if(!alive()||!resumed||screenHost==null)return;removeNotice(false);LinearLayout notice=row();notice.setPadding(dp(16),dp(6),dp(6),dp(6));notice.setBackground(background(0xFF343434,24));TextView label=text(value,14,WHITE,false);notice.addView(label,new LinearLayout.LayoutParams(0,-2,1));Button close=roundIcon(R.drawable.ic_close,false,"Dismiss message");close.setBackgroundColor(Color.TRANSPARENT);close.setOnClickListener(v -> removeNotice(true));notice.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.leftMargin=dp(18);lp.rightMargin=dp(18);int safeBottom=0;WindowInsets insets=screenHost.getRootWindowInsets();if(insets!=null)safeBottom=Math.max(insets.getInsets(WindowInsets.Type.systemBars()).bottom,insets.getInsets(WindowInsets.Type.ime()).bottom);lp.bottomMargin=safeBottom+(!settingsVisible&&!authVisible&&composer!=null?composer.getHeight()+dp(26):dp(20));transientNotice=notice;screenHost.addView(notice,lp);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);UiMotion.reveal(notice,0);int timeout=((android.view.accessibility.AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE)).getRecommendedTimeoutMillis(3000,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT|android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);noticeDismiss=() -> removeNotice(true);handler.postDelayed(noticeDismiss,timeout);
    }
    private void removeNotice(boolean animate){if(noticeDismiss!=null){handler.removeCallbacks(noticeDismiss);noticeDismiss=null;}if(transientNotice==null)return;View old=transientNotice;transientNotice=null;Runnable remove=()->{if(old.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)old.getParent()).removeView(old);};old.animate().cancel();if(animate&&UiMotion.enabled())old.animate().alpha(0).translationY(dp(8)).setDuration(UiMotion.EXIT).withEndAction(remove).start();else remove.run();}
    private void errorDialog(String message) { if(alive())showDialog(new TangoSheet.Builder(this).setTitle("Needs attention").setMessage(message).setPositiveButton("OK",null)); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=10)return;
        boolean requested=shouldListen;
        if(!requested)return;
        if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED) {
            // Permission UI may deliver this callback just before this activity resumes.
            resumePendingVoice();
        } else {shouldListen=false;microphoneIcon(false);voiceStatus("Allow microphone access to speak.");}
    }
    @Override public void onBackPressed() {if(!activeSheets.isEmpty()){activeSheets.get(activeSheets.size()-1).cancel();return;}if(drawerNavigation!=null){drawerNavigation=null;closeDrawer(true);return;}if(composerOverlay!=null)dismissComposerOverlay(true);else if(drawerVisible)closeDrawer(true);else if(settingsVisible)showEntry();else super.onBackPressed();}
    private boolean inside(View view,float x,float y) {if(view==null||!view.isShown())return false;int[] at=new int[2];view.getLocationOnScreen(at);return x>=at[0]&&x<=at[0]+view.getWidth()&&y>=at[1]&&y<=at[1]+view.getHeight();}
    private boolean overConversationControl(View view,float x,float y) {
        if(!inside(view,x,y))return false;if(view instanceof Button||view instanceof EditText||(view instanceof TextView&&((TextView)view).isTextSelectable()))return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(overConversationControl(group.getChildAt(i),x,y))return true;}return false;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(drawerNavigation!=null)return true;if(!activeSheets.isEmpty())return super.dispatchTouchEvent(event);
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){touchStartX=event.getRawX();touchStartY=event.getRawY();drawerDragging=false;gestureStartedOpen=drawerVisible;gestureStartProgress=drawerProgress;drawerGestureCandidate=!authVisible&&!settingsVisible&&composerOverlay==null&&(drawerVisible||(inside(conversation,touchStartX,touchStartY)&&!overConversationControl(conversation,touchStartX,touchStartY)));if(drawerVelocity!=null)drawerVelocity.recycle();drawerVelocity=VelocityTracker.obtain();}
        if(drawerVelocity!=null)drawerVelocity.addMovement(event);
        if(action==MotionEvent.ACTION_POINTER_DOWN)drawerGestureCandidate=false;
        if(action==MotionEvent.ACTION_MOVE&&drawerGestureCandidate){float dx=event.getRawX()-touchStartX,dy=event.getRawY()-touchStartY;int slop=ViewConfiguration.get(this).getScaledTouchSlop();
            if(!drawerDragging&&Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx))drawerGestureCandidate=false;
            if(!drawerDragging&&Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.4f&&(gestureStartedOpen||dx>0)){
                MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                if(!drawerVisible){openDrawer(false);gestureStartProgress=0;}if(drawerAnimation!=null){drawerAnimation.cancel();drawerAnimation=null;gestureStartProgress=drawerProgress-dx/drawerWidth;}drawerDragging=true;
            }
            if(drawerDragging){setDrawerProgress(gestureStartProgress+dx/drawerWidth);return true;}
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){boolean consumed=drawerDragging;
            if(drawerDragging){float speed=0;if(drawerVelocity!=null){drawerVelocity.computeCurrentVelocity(1000);speed=drawerVelocity.getXVelocity();}boolean open=action==MotionEvent.ACTION_CANCEL?gestureStartedOpen:Math.abs(speed)>dp(400)?speed>0:drawerProgress>.45f;animateDrawer(open);}
            drawerDragging=false;drawerGestureCandidate=false;if(drawerVelocity!=null){drawerVelocity.recycle();drawerVelocity=null;}if(consumed)return true;
        }
        if(drawerDragging)return true;
        return super.dispatchTouchEvent(event);
    }
}
