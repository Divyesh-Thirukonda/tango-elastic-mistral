package com.tango.authsmoke;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Tests actual owned bytes, durable drafts, provider camera capture, and Responses wire payloads offline. */
public final class AttachmentChecks {
    private static Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object call(Object target,String method,Class<?>[] signature,Object... args)throws Exception {
        Class<?> type=target instanceof Class?(Class<?>)target:target.getClass();Method found=type.getDeclaredMethod(method,signature);found.setAccessible(true);
        try{return found.invoke(target instanceof Class?null:target,args);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw(Exception)error.getCause();throw new AssertionError(error.getCause());}
    }
    private static JSONObject imported(Object store,String account,String thread,String name,String mime,byte[] bytes)throws Exception{return(JSONObject)call(store,"importStream",new Class[]{String.class,String.class,String.class,String.class,InputStream.class},account,thread,name,mime,new ByteArrayInputStream(bytes));}
    private static JSONObject metadata(Object store,String account,String thread,String id)throws Exception{return(JSONObject)call(store,"metadata",new Class[]{String.class,String.class,String.class},account,thread,id);}
    private static JSONArray payload(Object store,String account,String thread,JSONArray attached)throws Exception{return(JSONArray)call(store,"modelContent",new Class[]{String.class,String.class,JSONArray.class},account,thread,attached);}
    private static JSONObject part(JSONArray content,String type)throws Exception{for(int i=0;i<content.length();i++){JSONObject item=content.getJSONObject(i);if(type.equals(item.optString("type")))return item;}throw new AssertionError("Missing model content type: "+type);}
    private static void delete(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);}file.delete();}
    private interface Checked {void run()throws Exception;}
    private static void rejects(Checked action,String message)throws Exception{boolean rejected=false;try{action.run();}catch(Exception error){rejected=true;}require(rejected,message);}
    private static byte[] imageBytes()throws Exception{Bitmap bitmap=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);try(ByteArrayOutputStream output=new ByteArrayOutputStream()){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);return output.toByteArray();}finally{bitmap.recycle();}}
    private static byte[] pdfBytes()throws Exception{PdfDocument pdf=new PdfDocument();try(ByteArrayOutputStream output=new ByteArrayOutputStream()){PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(100,100,1).create());page.getCanvas().drawColor(Color.WHITE);pdf.finishPage(page);pdf.writeTo(output);return output.toByteArray();}finally{pdf.close();}}
    public static void run(Instrumentation test,Context context,JSONObject checks)throws Exception {
        ClassLoader loader=context.getClassLoader();Class<?> type=loader.loadClass("com.tango.phone.AttachmentStore"),threadType=loader.loadClass("com.tango.phone.ThreadStore");
        File files=new File(context.getCacheDir(),"attachment-checks");delete(files);files.mkdirs();Object store=type.getConstructor(File.class).newInstance(files),threads=threadType.getConstructor(File.class).newInstance(files);
        String account="attachment-owner",thread=(String)call(threads,"active",new Class[]{String.class},account);
        JSONObject text=imported(store,account,thread,"notes.txt","text/plain","Actual attachment fixture contents Ω".getBytes(StandardCharsets.UTF_8));
        byte[] imageData=imageBytes(),pdfData=pdfBytes();
        JSONObject image=imported(store,account,thread,"screen.png","image/png",imageData);
        JSONObject pdf=imported(store,account,thread,"brief.pdf","application/pdf",pdfData);
        JSONArray attached=new JSONArray().put(text).put(image).put(pdf);
        call(threads,"attachments",new Class[]{String.class,String.class,JSONArray.class},account,thread,attached);
        require(((JSONArray)call(threads,"list",new Class[]{String.class,String.class,boolean.class},account,"",false)).length()==1,"Attachment-only draft hidden from history");
        require(!thread.equals(call(threads,"create",new Class[]{String.class},account)),"New chat reused an attachment-only draft");
        Object reopened=threadType.getConstructor(File.class).newInstance(files);JSONObject saved=(JSONObject)call(reopened,"get",new Class[]{String.class,String.class},account,thread);
        require(saved.getJSONArray("attachments").length()==3,"Attachment drafts lost on restart");
        Object freshStore=type.getConstructor(File.class).newInstance(files);require(metadata(freshStore,account,thread,text.getString("id")).optLong("size")>0,"Private attachment bytes lost on restart");
        rejects(()->metadata(freshStore,"other-account",thread,text.getString("id")),"Other account read attachment bytes");
        rejects(()->metadata(freshStore,account,UUID.randomUUID().toString(),text.getString("id")),"Other thread read attachment bytes");
        rejects(()->metadata(freshStore,account,thread,"../../secret"),"Attachment path traversal accepted");
        require(!saved.toString().contains("Actual attachment fixture"),"File contents stored in thread metadata");
        checks.put("attachmentDraftsBytesRestartAccountThreadIsolation",true);
        JSONArray content=payload(freshStore,account,thread,attached);
        require(content.toString().contains("Actual attachment fixture contents Ω"),"Text attachment content missing from model input");
        String imageUrl=part(content,"input_image").getString("image_url"),fileData=part(content,"input_file").getString("file_data");
        require(imageUrl.startsWith("data:image/png;base64,") && java.util.Arrays.equals(imageData,android.util.Base64.decode(imageUrl.substring(imageUrl.indexOf(',')+1),android.util.Base64.DEFAULT)),"Image attachment pixels missing from model input");
        require(fileData.startsWith("data:application/pdf;base64,") && java.util.Arrays.equals(pdfData,android.util.Base64.decode(fileData.substring(fileData.indexOf(',')+1),android.util.Base64.DEFAULT)),"PDF attachment bytes missing from model input");
        JSONObject task=new JSONObject().put("goal","Read the three attached files").put("observation",new JSONObject().put("screenshot","PHONE_SCREENSHOT_FIXTURE")).put("attachmentInputs",content).put("attachments",attached);
        JSONObject request=(JSONObject)call(loader.loadClass("com.tango.phone.ApiClient"),"buildRequest",new Class[]{JSONObject.class,String.class},task,"fixture-model");
        JSONArray wire=request.getJSONArray("input").getJSONObject(0).getJSONArray("content");
        require(wire.length()==8,"Wire payload dropped attachment parts or phone screenshot");
        require(!wire.getJSONObject(0).getString("text").contains("data:image/"),"Encoded attachment bytes duplicated into prompt JSON");
        require(wire.getJSONObject(1).getString("image_url").equals("data:image/jpeg;base64,PHONE_SCREENSHOT_FIXTURE"),"Attachment replaced live phone screenshot");
        checks.put("attachmentsActualTextImagePdfBytesInResponsesPayload",true);
        rejects(()->imported(freshStore,account,thread,"program.zip","application/zip",new byte[]{80,75,3,4}),"Unsupported archive silently accepted");
        rejects(()->imported(freshStore,account,thread,"bad.txt","text/plain",new byte[]{(byte)255,(byte)254,0}),"Invalid text encoding silently accepted");
        rejects(()->imported(freshStore,account,thread,"empty.txt","text/plain",new byte[0]),"Empty attachment accepted");
        rejects(()->imported(freshStore,account,thread,"large.txt","text/plain",new byte[1024*1024+1]),"Oversized text accepted");
        rejects(()->imported(freshStore,account,thread,"large.bin","application/octet-stream",new byte[10*1024*1024+1]),"Oversized file accepted");
        checks.put("attachmentUnsupportedEmptyInvalidAndOversizeRejected",true);
        call(freshStore,"remove",new Class[]{String.class,String.class,String.class},account,thread,text.getString("id"));
        rejects(()->metadata(freshStore,account,thread,text.getString("id")),"Removing attachment retained owned bytes");
        call(reopened,"delete",new Class[]{String.class,String.class},account,thread);
        rejects(()->metadata(freshStore,account,thread,image.getString("id")),"Deleting chat retained attachment bytes");
        String clearThread=(String)call(reopened,"active",new Class[]{String.class},account);JSONObject clearFile=imported(freshStore,account,clearThread,"clear.txt","text/plain",new byte[]{65});
        call(reopened,"clear",new Class[]{String.class},account);
        rejects(()->metadata(freshStore,account,clearThread,clearFile.getString("id")),"Clearing chats retained attachment bytes");
        checks.put("attachmentRemoveDeleteClearOwnFiles",true);delete(files);
        cameraAndServiceDraft(test,context,type,checks);
    }
    private static void cameraAndServiceDraft(Instrumentation test,Context context,Class<?> type,JSONObject checks)throws Exception {
        Class<?> agent=context.getClassLoader().loadClass("com.tango.phone.AgentService");String original=(String)call(agent,"activeThreadId",new Class[]{Context.class},context);String[] thread={null};
        Throwable[] failure={null};test.runOnMainSync(()->{try{thread[0]=(String)call(agent,"newThread",new Class[]{Context.class},context);}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
        Uri capture=(Uri)call(type,"beginCamera",new Class[]{Context.class},context);
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(capture,"w");FileOutputStream output=new FileOutputStream(fd.getFileDescriptor())){output.write(imageBytes());}
        JSONObject attached=(JSONObject)call(type,"finishCamera",new Class[]{Context.class,Uri.class,boolean.class},context,capture,true);
        JSONArray drafts=(JSONArray)call(agent,"threadAttachments",new Class[]{Context.class},context);require(drafts.length()==1&&attached.getString("id").equals(drafts.getJSONObject(0).getString("id")),"Full-resolution camera output not imported into draft");
        Bitmap preview=(Bitmap)call(type,"thumbnail",new Class[]{Context.class,JSONObject.class,int.class},context,attached,96);require(preview!=null&&preview.getWidth()==24,"Camera attachment has no real preview");preview.recycle();
        rejects(()->context.getContentResolver().openFileDescriptor(capture,"r"),"Completed camera grant still exposed capture file");
        Uri cancelled=(Uri)call(type,"beginCamera",new Class[]{Context.class},context);
        require(call(type,"finishCamera",new Class[]{Context.class,Uri.class,boolean.class},context,cancelled,false)==null,"Cancelled camera added a file");
        rejects(()->context.getContentResolver().openFileDescriptor(cancelled,"r"),"Cancelled camera file remains accessible");
        require(((JSONArray)call(agent,"threadAttachments",new Class[]{Context.class},context)).length()==1,"Cancel changed existing attachment draft");
        call(agent,"removeThreadAttachment",new Class[]{Context.class,String.class},context,attached.getString("id"));require(((JSONArray)call(agent,"threadAttachments",new Class[]{Context.class},context)).length()==0,"Removing chip did not remove draft metadata");
        Uri sending=(Uri)call(type,"beginCamera",new Class[]{Context.class},context);
        try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(sending,"w");FileOutputStream output=new FileOutputStream(fd.getFileDescriptor())){output.write(imageBytes());}
        JSONObject sent=(JSONObject)call(type,"finishCamera",new Class[]{Context.class,Uri.class,boolean.class},context,sending,true);
        Object service=agent.getField("instance").get(null);require(service!=null,"Attachment service fixture missing");
        ExecutorService worker=(ExecutorService)field(service,"worker");CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{held.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});require(held.await(3,TimeUnit.SECONDS),"Could not hold worker before attachment send");
        try {
            test.runOnMainSync(()->{try{call(service,"startTask",new Class[]{String.class},"Read the attached photo");call(service,"pauseTask",new Class[]{String.class},"Attachment fixture pause");}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
            require(((JSONArray)call(agent,"threadAttachments",new Class[]{Context.class},context)).length()==0,"Sent attachment left in composer draft");
            JSONArray journal=(JSONArray)call(agent,"journalSnapshot",new Class[]{});require(journal.getJSONObject(0).getJSONArray("attachments").getJSONObject(0).getString("id").equals(sent.getString("id")),"Sent journal dropped attachment metadata");
            String account=(String)call(type,"authorizedAccount",new Class[]{Context.class},context);Object store=type.getConstructor(File.class).newInstance(context.getFilesDir());JSONObject followup=imported(store,account,thread[0],"followup.txt","text/plain","Use these additional details".getBytes(StandardCharsets.UTF_8));
            call(agent,"addImportedAttachment",new Class[]{Context.class,String.class,String.class,JSONObject.class},context,account,thread[0],followup);
            test.runOnMainSync(()->{try{call(service,"resumeTask",new Class[]{String.class},"Also check this document");call(service,"pauseTask",new Class[]{String.class},"Attachment fixture pause");}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
            JSONArray sentAttachments=(JSONArray)field(service,"sentAttachments");require(sentAttachments.length()==2,"Follow-up replaced or dropped earlier attachment");
            JSONArray model=payload(store,account,thread[0],sentAttachments);require(part(model,"input_image").getString("image_url").startsWith("data:image/png;base64,")&&model.toString().contains("Use these additional details"),"Follow-up request lacks actual original and new files");
            test.runOnMainSync(()->{try{call(agent,"selectThread",new Class[]{Context.class,String.class},context,original);call(agent,"selectThread",new Class[]{Context.class,String.class},context,thread[0]);}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
            require(((JSONArray)field(service,"sentAttachments")).length()==2,"Restoring thread lost sent attachment inputs");
            checks.put("attachmentSendFollowupJournalAndRestorePreserveActualFiles",true);
        }finally{release.countDown();}
        test.runOnMainSync(()->{try{call(agent,"selectThread",new Class[]{Context.class,String.class},context,original);if(!original.equals(thread[0]))call(agent,"deleteThread",new Class[]{Context.class,String.class},context,thread[0]);}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
        checks.put("attachmentCameraFullSizeProviderPreviewCancelAndRemove",true);
    }
}
