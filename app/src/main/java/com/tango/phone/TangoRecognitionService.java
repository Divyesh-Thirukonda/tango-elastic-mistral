package com.tango.phone;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.List;

/** Required assistant-role recognizer, forwarding to Android's existing speech engine. */
public final class TangoRecognitionService extends RecognitionService {
    private SpeechRecognizer recognizer;
    private Callback active;
    private Intent activeIntent;
    private boolean stopping;

    @Override protected void onStartListening(Intent intent, Callback callback) {
        if (active != null) {
            try { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); } catch (RemoteException ignored) { }
            return;
        }
        active = callback;
        activeIntent = new Intent(intent);
        stopping = false;
        try {
            beginEngine(callback, Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        } catch (SecurityException error) {
            send(callback, () -> callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
            release();
        } catch (RuntimeException error) {
            send(callback, () -> callback.error(SpeechRecognizer.ERROR_CLIENT));
            release();
        }
    }

    private void beginEngine(Callback callback, boolean onDevice) {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) old.destroy();
        final SpeechRecognizer engine;
        if (onDevice && Build.VERSION.SDK_INT >= 31) engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        else {
            ComponentName provider = externalProvider();
            if (provider == null) {
                send(callback, () -> callback.error(SpeechRecognizer.ERROR_CLIENT));
                release();
                return;
            }
            engine = SpeechRecognizer.createSpeechRecognizer(this, provider);
        }
        recognizer = engine;
        engine.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle parameters) { forward(engine, callback, () -> callback.readyForSpeech(parameters)); }
                @Override public void onBeginningOfSpeech() { forward(engine, callback, callback::beginningOfSpeech); }
                @Override public void onRmsChanged(float rms) { forward(engine, callback, () -> callback.rmsChanged(rms)); }
                @Override public void onBufferReceived(byte[] buffer) { forward(engine, callback, () -> callback.bufferReceived(buffer)); }
                @Override public void onEndOfSpeech() { forward(engine, callback, callback::endOfSpeech); }
                @Override public void onError(int error) {
                    if (recognizer != engine || active != callback) return;
                    if (onDevice && !stopping && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                        try { beginEngine(callback, false); }
                        catch (SecurityException failure) {
                            send(callback, () -> callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
                            release();
                        } catch (RuntimeException failure) {
                            send(callback, () -> callback.error(SpeechRecognizer.ERROR_CLIENT));
                            release();
                        }
                        return;
                    }
                    send(callback, () -> callback.error(error));
                    if (active == callback) release();
                }
                @Override public void onResults(Bundle results) {
                    forward(engine, callback, () -> callback.results(results));
                    if (recognizer == engine && active == callback) release();
                }
                @Override public void onPartialResults(Bundle results) { forward(engine, callback, () -> callback.partialResults(results)); }
                @Override public void onSegmentResults(Bundle results) {
                    if (Build.VERSION.SDK_INT >= 33) forward(engine, callback, () -> callback.segmentResults(results));
                }
                @Override public void onEndOfSegmentedSession() {
                    if (Build.VERSION.SDK_INT >= 33) forward(engine, callback, callback::endOfSegmentedSession);
                    if (recognizer == engine && active == callback) release();
                }
                @Override public void onLanguageDetection(Bundle results) {
                    if (Build.VERSION.SDK_INT >= 34) forward(engine, callback, () -> callback.languageDetection(results));
                }
                @Override public void onEvent(int event, Bundle parameters) { /* Reserved; Callback has no event counterpart. */ }
            });
        engine.startListening(new Intent(activeIntent));
    }

    @Override protected void onStopListening(Callback callback) {
        if (recognizer != null && active == callback) {
            stopping = true;
            recognizer.stopListening();
        }
    }

    @Override protected void onCancel(Callback callback) {
        if (active == callback) release();
    }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }

    private ComponentName externalProvider() {
        String preferredSetting = Settings.Secure.getString(getContentResolver(), "voice_recognition_service");
        ComponentName preferred = preferredSetting == null ? null : ComponentName.unflattenFromString(preferredSetting);
        ComponentName system = null;
        ComponentName fallback = null;
        List<ResolveInfo> providers = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        for (ResolveInfo provider : providers) {
            if (provider.serviceInfo == null || !provider.serviceInfo.enabled || !provider.serviceInfo.exported
                    || provider.serviceInfo.packageName.equals(getPackageName())) continue;
            ComponentName component = new ComponentName(provider.serviceInfo.packageName, provider.serviceInfo.name);
            if (component.equals(preferred)) return component;
            if (fallback == null) fallback = component;
            if (system == null && (provider.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) system = component;
        }
        return system == null ? fallback : system;
    }

    private void release() {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        active = null;
        activeIntent = null;
        if (old != null) old.destroy();
    }

    private void send(Callback callback, Forward operation) {
        if (active != callback) return;
        try { operation.run(); }
        catch (RemoteException ignored) { if (active == callback) release(); }
    }

    private void forward(SpeechRecognizer engine, Callback callback, Forward operation) {
        if (recognizer == engine && active == callback) send(callback, operation);
    }

    private interface Forward { void run() throws RemoteException; }
}
