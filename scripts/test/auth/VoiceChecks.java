package com.tango.authsmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.EditText;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Delivers a synthetic result to the app's actual callback; never opens the microphone. */
public final class VoiceChecks {
    private final Instrumentation test;private final Context context;private final Activity activity;private final JSONObject checks;private final Class<?> agent;private final AtomicInteger requests=new AtomicInteger();
    private VoiceChecks(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception{this.test=test;this.context=context;this.activity=activity;this.checks=checks;agent=context.getClassLoader().loadClass("com.tango.phone.AgentService");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static Object call(Object value,String name)throws Exception{java.lang.reflect.Method m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);try{return m.invoke(value);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw new RuntimeException(e.getCause());}}
    private interface Checked {void run()throws Exception;}
    private void main(Checked work)throws Exception {
        Throwable[] failure={null};test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Exception)throw(Exception)failure[0];if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private RecognitionListener listener()throws Exception {return listener(false);}
    private RecognitionListener listener(boolean onDevice)throws Exception {
        for(int i=1;i<=60;i++)try{Class<?> candidate=context.getClassLoader().loadClass("com.tango.phone.MainActivity$"+i);if(!RecognitionListener.class.isAssignableFrom(candidate))continue;Constructor<?> constructor=candidate.getDeclaredConstructors()[0];constructor.setAccessible(true);Class<?>[] types=constructor.getParameterTypes();Object[] arguments=new Object[types.length];for(int n=0;n<types.length;n++){if(types[n].isInstance(activity))arguments[n]=activity;else if(types[n]==int.class)arguments[n]=field(activity,"voiceGeneration");else if(types[n]==boolean.class)arguments[n]=onDevice;else throw new AssertionError("Unexpected voice callback capture");}return(RecognitionListener)constructor.newInstance(arguments);}catch(ClassNotFoundException ignored){}
        throw new AssertionError("Actual voice result callback was not found");
    }
    private void prepare(boolean auto)throws Exception{context.getSharedPreferences("tango",0).edit().putBoolean("auto_run_voice",auto).putBoolean("listen_on_launch",false).commit();main(()->context.startActivity(new Intent().setClassName("com.tango.phone","com.tango.phone.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)));test.waitForIdleSync();SystemClock.sleep(120);main(()->{agent.getMethod("newThread",Context.class).invoke(null,context);call(activity,"showHome");});}
    private void results(String command)throws Exception{RecognitionListener listener=listener();Bundle results=new Bundle();ArrayList<String> choices=new ArrayList<>();choices.add(command);results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,choices);main(()->listener.onResults(results));}
    private void beginSyntheticRecording()throws Exception {
        require(field(activity,"recognizer")==null,"Synthetic voice checks must never open the microphone");
        java.lang.reflect.Method start=activity.getClass().getDeclaredMethod("microphoneIcon",boolean.class);start.setAccessible(true);start.invoke(activity,true);
    }
    private void assertMicrophone(boolean recording,boolean enabled)throws Exception {
        Button button=(Button)field(activity,"mic");require(button.isEnabled()==enabled,"Microphone enabled state did not match recording lifecycle");
        String label=!enabled?"Transcribing voice command":recording?"Finish voice command":"Speak a command";
        require(label.contentEquals(button.getContentDescription()),"Microphone action label did not match its state");
        Drawable icon=(Drawable)field(button,"icon");
        // Clone the actual selected vector before rasterizing: the View may have populated
        // its hardware cache at a different size. Never mutate the live UI to inspect it.
        Drawable actual=icon.getConstantState().newDrawable(activity.getResources(),activity.getTheme()).mutate();actual.setAlpha(255);
        int resource=context.getResources().getIdentifier(recording?"ic_stop":"ic_mic","drawable",context.getPackageName());
        Drawable expected=activity.getDrawable(resource).mutate();expected.setTint(Color.WHITE);expected.setAlpha(255);
        int size=48;Bitmap actualPixels=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888),expectedPixels=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        actual.setBounds(0,0,size,size);actual.draw(new Canvas(actualPixels));expected.setBounds(0,0,size,size);expected.draw(new Canvas(expectedPixels));
        // Hardware-cached vectors resample edge alpha differently from a fresh software
        // render. Verify silhouette and scale, allowing only one pixel at those edges.
        int intersection=0,union=0,actualCoverage=0,expectedCoverage=0;boolean edgeOnly=true;
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            int a=Color.alpha(actualPixels.getPixel(x,y)),b=Color.alpha(expectedPixels.getPixel(x,y));actualCoverage+=a;expectedCoverage+=b;
            boolean actualFilled=a>128,expectedFilled=b>128;if(actualFilled&&expectedFilled)intersection++;if(actualFilled||expectedFilled)union++;
            if(actualFilled!=expectedFilled){Bitmap target=actualFilled?expectedPixels:actualPixels;boolean nearby=false;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx>=0&&ny>=0&&nx<size&&ny<size&&Color.alpha(target.getPixel(nx,ny))>128)nearby=true;}edgeOnly&=nearby;}
        }
        double overlap=union==0?0:(double)intersection/union,coverageRatio=expectedCoverage==0?0:(double)actualCoverage/expectedCoverage;
        boolean correctShape=overlap>=.95&&Math.abs(coverageRatio-1)<=.02&&edgeOnly;
        if(!correctShape){try(java.io.FileOutputStream output=context.openFileOutput("voice-icon-actual.png",0)){actualPixels.compress(Bitmap.CompressFormat.PNG,100,output);}try(java.io.FileOutputStream output=context.openFileOutput("voice-icon-expected.png",0)){expectedPixels.compress(Bitmap.CompressFormat.PNG,100,output);}}
        actualPixels.recycle();expectedPixels.recycle();require(correctShape,"Microphone drawable did not immediately become "+(recording?"the stop square":"the microphone")+" (silhouette overlap="+overlap+", coverage ratio="+coverageRatio+", differences confined to one-pixel edges="+edgeOnly+")");
    }
    private void recordingControls()throws Exception {
        prepare(false);
        main(()->{
            beginSyntheticRecording();assertMicrophone(true,true);RecognitionListener active=listener();int ticket=(int)field(activity,"voiceGeneration");
            // A delayed auto-listen must not create another recognizer or wait for another tap.
            call(activity,"listen");require((int)field(activity,"voiceGeneration")==ticket&&field(activity,"recognizer")==null,"Duplicate listen launched a second speech session");assertMicrophone(true,true);
            ((Button)field(activity,"mic")).performClick();require((boolean)field(activity,"listening")&&(boolean)field(activity,"voiceFinishing"),"One stop tap did not finish the active recording");assertMicrophone(false,false);
            active.onReadyForSpeech(new Bundle());assertMicrophone(false,false);
            Bundle words=new Bundle();ArrayList<String> choices=new ArrayList<>();choices.add("Synthetic transcript");words.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,choices);active.onResults(words);assertMicrophone(false,true);
            require("Synthetic transcript".contentEquals(((EditText)field(activity,"command")).getText()),"Finishing discarded the final transcript");
            choices.set(0,"Stale transcript");active.onPartialResults(words);active.onReadyForSpeech(new Bundle());assertMicrophone(false,true);
            require("Synthetic transcript".contentEquals(((EditText)field(activity,"command")).getText()),"A completed speech session overwrote the current draft");
            beginSyntheticRecording();RecognitionListener cancelled=listener();call(activity,"stopListening");cancelled.onReadyForSpeech(new Bundle());assertMicrophone(false,true);
            beginSyntheticRecording();RecognitionListener failing=listener();failing.onError(SpeechRecognizer.ERROR_NO_MATCH);assertMicrophone(false,true);
            beginSyntheticRecording();RecognitionListener fallback=listener(true);fallback.onError(12);assertMicrophone(true,true);
            ((Button)field(activity,"mic")).performClick();assertMicrophone(false,false);
        });
        // Let the queued unsupported-on-device fallback run after the user's stop tap.
        test.waitForIdleSync();main(()->{assertMicrophone(false,true);require(field(activity,"recognizer")==null,"Cancelled speech fallback opened the microphone");});
        checks.put("voiceRecordingShowsSquareWithoutSecondTap",true);checks.put("voiceSingleStopTapShowsTranscribing",true);checks.put("voiceFinalTranscriptSurvivesStop",true);checks.put("voiceStaleCallbacksCannotChangeComposer",true);checks.put("voiceStoppedFallbackDoesNotRestartMicrophone",true);checks.put("voiceDuplicateLaunchDoesNotRestartRecording",true);checks.put("voiceControlChecksUseNoMicrophone",true);
    }
    private void run()throws Exception {
        FixtureHttp.Responder previous=FixtureHttp.responder;FixtureHttp.responder=(url,method,body)->{if(url.getHost().equals("api.openai.com")&&url.getPath().equals("/v1/responses")&&body.contains("VOICE_AUTORUN_FIXTURE")){requests.incrementAndGet();return new FixtureHttp.Response(200,new JSONObject().put("object","response").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",new JSONObject().put("action",new JSONObject().put("type","done").put("summary","Synthetic voice task complete.")).toString()))))).toString());}return previous.answer(url,method,body);};
        try {
            recordingControls();
            prepare(true);results("VOICE_EDIT_CANCEL_FIXTURE Find the club form.");require(field(activity,"scheduledRun")!=null,"Voice result did not create its edit window");main(()->((EditText)field(activity,"command")).requestFocus());SystemClock.sleep(1250);require(field(activity,"scheduledRun")==null&&!((String)agent.getField("goal").get(null)).contains("VOICE_EDIT_CANCEL_FIXTURE")&&requests.get()==0,"Editing did not cancel voice auto-run");checks.put("voiceEditFocusCancelsAutoRun",true);
            prepare(false);results("VOICE_MANUAL_FIXTURE Find the club form.");SystemClock.sleep(1150);require(field(activity,"scheduledRun")==null&&!((String)agent.getField("goal").get(null)).contains("VOICE_MANUAL_FIXTURE")&&requests.get()==0,"Account preference did not disable auto-run");checks.put("voiceAutoRunPreferenceCanDisableCountdown",true);
            prepare(true);long began=SystemClock.elapsedRealtime();results("VOICE_AUTORUN_FIXTURE Keep Settings open.");long until=began+2400;while(SystemClock.elapsedRealtime()<until&&!((String)agent.getField("goal").get(null)).contains("VOICE_AUTORUN_FIXTURE"))SystemClock.sleep(20);long elapsed=SystemClock.elapsedRealtime()-began;require(((String)agent.getField("goal").get(null)).contains("VOICE_AUTORUN_FIXTURE")&&elapsed>=800&&elapsed<2200,"Voice task did not start within the one-second edit window: "+elapsed);checks.put("voiceOneSecondAutoRunSubmitsNativeTask",true);checks.put("voiceTimingsMs",new JSONObject().put("autoRunSubmission",elapsed));
            until=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<until&&!"done".equals(agent.getField("state").get(null)))SystemClock.sleep(30);require("done".equals(agent.getField("state").get(null))&&requests.get()==1,"Synthetic voice task did not finish through the native harness");checks.put("voiceSyntheticResultCompletesNativeTask",true);checks.put("offlineVoiceInferenceRequests",requests.get());
        } finally {FixtureHttp.responder=previous;main(()->context.stopService(new Intent().setClassName("com.tango.phone","com.tango.phone.AgentService")));}
    }
    public static void run(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception{new VoiceChecks(test,context,activity,checks).run();}
    public static void runControls(Instrumentation test,Context context,Activity activity,JSONObject checks)throws Exception{new VoiceChecks(test,context,activity,checks).recordingControls();}
}
