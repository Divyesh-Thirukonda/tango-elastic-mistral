package com.tango.authsmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Intercepts Android picker/camera intents. All photo pixels are generated fixtures. */
public final class AttachmentUiChecks {
    private final Instrumentation test;
    private final Context context;
    private final Activity activity;
    private final JSONObject checks;
    private final Class<?> agent;
    private AttachmentUiChecks(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception {
        this.test=test;this.context=context;this.activity=activity;this.checks=checks;
        agent=context.getClassLoader().loadClass("com.tango.phone.AgentService");
    }
    private interface Checked {void run()throws Exception;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static Object call(Object target,String name,Class<?>[] types,Object...arguments)throws Exception {
        java.lang.reflect.Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,arguments);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw(Exception)error.getCause();throw new RuntimeException(error.getCause());}
    }
    private void main(Checked work)throws Exception {
        final Throwable[] failure={null};test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Exception)throw(Exception)failure[0];if(failure[0]!=null)throw new AssertionError(failure[0]);test.waitForIdleSync();
    }
    private JSONArray attachments()throws Exception{return(JSONArray)agent.getMethod("threadAttachments",Context.class).invoke(null,context);}
    private static View find(View view,String label) {
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())||view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}}return null;
    }
    private static boolean hasThumbnail(View view) {
        if(view.getVisibility()!=View.VISIBLE)return false;
        if(view instanceof ImageView&&((ImageView)view).getDrawable() instanceof BitmapDrawable)return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasThumbnail(group.getChildAt(i)))return true;}return false;
    }
    private void waitForImport()throws Exception {
        long until=SystemClock.uptimeMillis()+4000;boolean[] importing={true};
        while(importing[0]&&SystemClock.uptimeMillis()<until){main(()->importing[0]=(boolean)field(activity,"importingAttachments"));if(importing[0])SystemClock.sleep(30);}
        require(!importing[0],"Attachment import did not finish");
    }
    private final class PickerMonitor extends Instrumentation.ActivityMonitor {
        final boolean photoSuccess;
        Intent outgoing;
        Uri camera;
        Throwable failure;
        int starts;
        PickerMonitor(boolean photoSuccess){this.photoSuccess=photoSuccess;}
        @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if(!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())&&!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()))return null;
            starts++;outgoing=new Intent(intent);
            try {
                require(field(activity,"attachmentThread").equals(agent.getMethod("activeThreadId",Context.class).invoke(null,context)),"Picker did not capture the current chat");
                Object auth=context.getClassLoader().loadClass("com.tango.phone.ChatGptAuth").getMethod("get",Context.class).invoke(null,context);
                require(field(activity,"attachmentAccount").equals(auth.getClass().getMethod("activeClientId").invoke(auth)),"Picker did not capture the current account");
                if(MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())) {
                    camera=intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                    require(camera!=null&&"content".equals(camera.getScheme()),"Camera output is not a content URI");
                    require(camera.equals(field(activity,"pendingCamera")),"Camera result destination is not retained");
                    require(new File(context.getFilesDir(),"camera-attachments/"+camera.getLastPathSegment()+".jpg").isFile(),"Camera destination was not created before launch");
                    if(photoSuccess){Bitmap fixture=Bitmap.createBitmap(20,16,Bitmap.Config.ARGB_8888);fixture.eraseColor(Color.rgb(44,122,212));try(OutputStream output=context.getContentResolver().openOutputStream(camera,"w")){require(output!=null&&fixture.compress(Bitmap.CompressFormat.JPEG,95,output),"Could not write the synthetic camera photo");}finally{fixture.recycle();}}
                }
            }catch(Throwable error){failure=error;}
            return new Instrumentation.ActivityResult(photoSuccess&&failure==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,null);
        }
    }
    private PickerMonitor launch(int request,boolean photoSuccess)throws Exception {
        PickerMonitor monitor=new PickerMonitor(photoSuccess);test.addMonitor(monitor);
        try {main(()->call(activity,"launchAttachment",new Class[]{int.class},request));}
        finally {test.removeMonitor(monitor);}
        if(monitor.failure instanceof Exception)throw(Exception)monitor.failure;if(monitor.failure!=null)throw new AssertionError(monitor.failure);
        require(monitor.starts==1&&monitor.outgoing!=null,"Attachment action did not launch exactly one native intent");waitForImport();return monitor;
    }
    private void documentIntents()throws Exception {
        String[] supported=(String[])context.getClassLoader().loadClass("com.tango.phone.AttachmentStore").getField("SUPPORTED_MIME_TYPES").get(null);
        for(int request:new int[]{20,21}) {
            Intent intent=launch(request,false).outgoing;
            require(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_OPENABLE),"Files/Photos did not launch the native openable document picker");
            require(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false),"Files/Photos picker does not allow multiple attachments");
            require((request==20?"*/*":"image/*").equals(intent.getType()),"Files/Photos picker has the wrong base MIME type");
            String[] actual=intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),expected=request==20?supported:new String[]{"image/jpeg","image/png","image/webp"};
            require(actual!=null&&new HashSet<>(Arrays.asList(actual)).equals(new HashSet<>(Arrays.asList(expected))),"Files/Photos picker does not filter to supported attachment types");
            require((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"Selected documents are not requested with read access");
            require(attachments().length()==0,"Cancelling a picker added an attachment");
        }
        checks.put("uiFilesNativePickerHasMultipleSelectionAndMimeFilter",true);checks.put("uiPhotosNativePickerRestrictsSupportedImages",true);checks.put("uiCancelledPickersLeaveDraftUnchanged",true);
    }
    private void cameraIntent(PickerMonitor monitor)throws Exception {
        Intent intent=monitor.outgoing;Uri output=monitor.camera;
        require(MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()),"Camera action did not launch Android image capture");
        require((context.getPackageName()+".attachments").equals(output.getAuthority()),"Camera output did not use Tango's private attachment provider");
        ClipData clip=intent.getClipData();require(clip!=null&&clip.getItemCount()==1&&output.equals(clip.getItemAt(0).getUri()),"Camera URI is missing from ClipData");
        int grants=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;require((intent.getFlags()&grants)==grants,"Camera intent lacks temporary read/write grants");
        require(field(activity,"pendingCamera")==null,"Completed/cancelled camera still has a pending output");
        for(String suffix:new String[]{".jpg",".json"})require(!new File(context.getFilesDir(),"camera-attachments/"+output.getLastPathSegment()+suffix).exists(),"Camera temporary file survived its result");
    }
    private void cameraDraft()throws Exception {
        PickerMonitor cancelled=launch(22,false);cameraIntent(cancelled);require(attachments().length()==0,"Cancelling the camera added an attachment");
        checks.put("uiCameraHasContentOutputClipDataAndTemporaryGrants",true);checks.put("uiCameraCancellationRemovesTemporaryFiles",true);
        PickerMonitor captured=launch(22,true);cameraIntent(captured);JSONArray values=attachments();require(values.length()==1,"A successful full-size camera result was not attached");JSONObject photo=values.getJSONObject(0);require("image/jpeg".equals(photo.getString("mime")),"Captured JPEG did not retain its image type");
        main(()->call(activity,"showHome",new Class[]{}));require(attachments().length()==1&&photo.getString("id").equals(attachments().getJSONObject(0).getString("id")),"Composer recreation lost the photo draft");
        boolean[] thumbnail={false};long until=SystemClock.uptimeMillis()+4000;
        while(!thumbnail[0]&&SystemClock.uptimeMillis()<until){main(()->thumbnail[0]=hasThumbnail((View)field(activity,"attachmentChips")));if(!thumbnail[0])SystemClock.sleep(30);}
        require(thumbnail[0],"Restored camera attachment has no image thumbnail");
        main(()->{View chips=(View)field(activity,"attachmentChips");require(find(chips,photo.getString("name"))!=null,"Restored attachment has no filename");View remove=find(chips,"Remove "+photo.getString("name"));require(remove instanceof Button,"Attachment has no accessible remove control");remove.performClick();});
        require(attachments().length()==0,"Remove attachment did not update the saved draft");main(()->require(((View)field(activity,"attachmentStrip")).getVisibility()==View.GONE,"Removing the last attachment left an empty strip"));
        checks.put("uiFullSizeCameraResultBecomesDraftAttachment",true);checks.put("uiAttachmentThumbnailSurvivesComposerRecreation",true);checks.put("uiAttachmentRemoveUpdatesSavedDraft",true);
    }
    private void run()throws Exception {
        require(attachments().length()==0,"Attachment UI checks require the idle empty composer fixture");
        try{documentIntents();cameraDraft();checks.put("uiAttachmentChecksUseNoCameraOrProviderInference",true);}
        finally {
            waitForImport();JSONArray leftovers=attachments();for(int i=0;i<leftovers.length();i++)agent.getMethod("removeThreadAttachment",Context.class,String.class).invoke(null,context,leftovers.getJSONObject(i).getString("id"));
            main(()->{call(activity,"dismissComposerOverlay",new Class[]{boolean.class},false);call(activity,"showHome",new Class[]{});});
        }
    }
    public static void run(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception {new AttachmentUiChecks(test,context,activity,checks).run();}
}
