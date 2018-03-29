package community.revteltech.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.content.pm.PackageManager;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.os.Parcelable;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.util.Log;

import com.facebook.react.bridge.*;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class NfcManager extends ReactContextBaseJavaModule implements ActivityEventListener, LifecycleEventListener {
    private static final String LOG_TAG = "NfcManager";
    private final List<IntentFilter> intentFilters = new ArrayList<IntentFilter>();
    private final ArrayList<String[]> techLists = new ArrayList<String[]>();
    private Context context;
    private ReactApplicationContext reactContext;
    private Boolean isForegroundEnabled = false;
    private Boolean isResumed = false;
    private WriteNdefRequest writeNdefRequest = null;
    private Intent savedIntent;
    private WritableMap savedParsed;

    class WriteNdefRequest {
        NdefMessage message;
        Callback callback;

        WriteNdefRequest(NdefMessage message, Callback callback) {
            this.message = message;
            this.callback = callback;
        }
    }

    public NfcManager(ReactApplicationContext reactContext) {
        super(reactContext);
        context = reactContext;
        this.reactContext = reactContext;
        reactContext.addActivityEventListener(this);
        reactContext.addLifecycleEventListener(this);
        Log.d(LOG_TAG, "NfcManager created");
    }

    @Override
    public String getName() {
        return "NfcManager";
    }

    @ReactMethod
    public void cancelNdefWrite(Callback callback) {
        synchronized(this) {
            if (writeNdefRequest != null) {
                writeNdefRequest.callback.invoke("cancelled");
                writeNdefRequest = null;
                callback.invoke();
            } else {
                callback.invoke("no writing request available");
            }
        }
    }

    @ReactMethod
    public void requestNdefWrite(String data, Callback callback) {
        synchronized(this) {
            if (!isForegroundEnabled) {
                callback.invoke("you should requestTagEvent first");
                return;
            }

            if (writeNdefRequest != null) {
                callback.invoke("You can only issue one request at a time");
            } else {
                try {
                    Log.d(LOG_TAG, data.toString());
                    NdefMessage message = new NdefMessage(
                            new NdefRecord(NdefRecord.TNF_UNKNOWN, new byte[0], new byte[0], Util.hexStringToByteArray(data)));
                    Log.d(LOG_TAG, message.toString());
                    writeNdefRequest = new WriteNdefRequest(
                            message,
                            callback // defer the callbackj
                    );

                } catch (IllegalArgumentException e) {
                    Log.d(LOG_TAG, e.toString());
                    callback.invoke("Illegal argument");
                } catch (NullPointerException e) {
                    Log.d(LOG_TAG, e.toString());
                    callback.invoke("Nullpointer exception");
                }
            }
        }
    }

    @ReactMethod
    public void start(Callback callback) {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(context);
        if (nfcAdapter != null) {
            Log.d(LOG_TAG, "start");
            callback.invoke();
        } else {
            Log.d(LOG_TAG, "not support in this device");
            callback.invoke("no nfc support");
        }
    }

    @ReactMethod
    public void isSupported(Callback callback){
        Log.d(LOG_TAG, "isSupported");
        boolean result = getReactApplicationContext().getCurrentActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
        callback.invoke(null,result);
    }

    @ReactMethod
    public void isEnabled(Callback callback) {
        Log.d(LOG_TAG, "isEnabled");
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(context);
        if (nfcAdapter != null) {
            callback.invoke(null, nfcAdapter.isEnabled());
        } else {
            callback.invoke(null, false);
        }
    }

    @ReactMethod
    public void goToNfcSetting(Callback callback) {
        Log.d(LOG_TAG, "goToNfcSetting");
        Activity currentActivity = getCurrentActivity();
        currentActivity.startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        callback.invoke();
    }

    @ReactMethod
    public void getLaunchTagEvent(Callback callback) {
        Activity currentActivity = getCurrentActivity();
        Intent launchIntent = currentActivity.getIntent();
        WritableMap nfcTag = parseNfcIntent(launchIntent);
        callback.invoke(null, nfcTag);
    }

    @ReactMethod
    private void registerTagEvent(String alertMessage, Boolean invalidateAfterFirstRead, Callback callback) {
        Log.d(LOG_TAG, "registerTag");
        isForegroundEnabled = true;

        // capture all mime-based dispatch NDEF
        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        try {
            ndef.addDataType("*/*");
        } catch (MalformedMimeTypeException e) {
            throw new RuntimeException("fail", e);
        }
        intentFilters.add(ndef);

        // capture all rest NDEF, such as uri-based
        intentFilters.add(new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED));
        techLists.add(new String[]{Ndef.class.getName()});

        // for those without NDEF, get them as tags
        intentFilters.add(new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED));

        if (isResumed) {
            enableDisableForegroundDispatch(true);
        }
        callback.invoke();
    }

    @ReactMethod
    private void unregisterTagEvent(Callback callback) {
        Log.d(LOG_TAG, "registerTag");
        isForegroundEnabled = false;
        intentFilters.clear();
        if (isResumed) {
            enableDisableForegroundDispatch(false);
        }
        callback.invoke();
    }

    @Override
    public void onHostResume() {
        Log.d(LOG_TAG, "onResume");
        isResumed = true;
        if (isForegroundEnabled) {
            enableDisableForegroundDispatch(true);
        }
    }

    @Override
    public void onHostPause() {
        Log.d(LOG_TAG, "onPause");
        isResumed = false;
        enableDisableForegroundDispatch(false);
    }

    @Override
    public void onHostDestroy() {
        Log.d(LOG_TAG, "onDestroy");
    }

    private void enableDisableForegroundDispatch(boolean enable) {
        Log.i(LOG_TAG, "enableForegroundDispatch, enable = " + enable);
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(context);
        Activity currentActivity = getCurrentActivity();

        if (nfcAdapter != null && !currentActivity.isFinishing()) {
            try {
                if (enable) {
                    nfcAdapter.enableForegroundDispatch(currentActivity, getPendingIntent(), getIntentFilters(), getTechLists());
                } else {
                    nfcAdapter.disableForegroundDispatch(currentActivity);
                }
            } catch (IllegalStateException e) {
                Log.w(LOG_TAG, "Illegal State Exception starting NFC. Assuming application is terminating.");
            }
        }
    }

    private PendingIntent getPendingIntent() {
        Activity activity = getCurrentActivity();
        Intent intent = new Intent(activity, activity.getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(activity, 0, intent, 0);
    }

    private IntentFilter[] getIntentFilters() {
        return intentFilters.toArray(new IntentFilter[intentFilters.size()]);
    }

    private String[][] getTechLists() {
        return techLists.toArray(new String[0][0]);
    }

    private void sendEvent(String eventName,
                           @Nullable WritableMap params) {
        getReactApplicationContext()
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(eventName, params);
    }

    private void sendEventWithJson(String eventName,
                                   JSONObject json) {
        try {
            WritableMap map = JsonConvert.jsonToReact(json);
            sendEvent(eventName, map);
        } catch (JSONException ex) {
            Log.d(LOG_TAG, "fireNdefEvent fail: " + ex);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "onReceive " + intent);
        }
    };

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        Log.d(LOG_TAG, "onActivityResult");
    }

    @Override
    public void onNewIntent(Intent intent) {
        this.savedIntent = intent;
        Log.d(LOG_TAG, "onNewIntent " + intent);
        WritableMap nfcTag = parseNfcIntent(intent);
        if (nfcTag != null) {
            sendEvent("NfcManagerDiscoverTag", nfcTag);
        }
    }

    private WritableMap parseNfcIntent(Intent intent) {
        Log.d(LOG_TAG, "parseIntent " + intent);
        String action = intent.getAction();
        Log.d(LOG_TAG, "action " + action);
        if (action == null) {
            return null;
        }

        WritableMap parsed = null;
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);

        if (action.equals(NfcAdapter.ACTION_NDEF_DISCOVERED)) {
            Ndef ndef = Ndef.get(tag);
            Parcelable[] messages = intent.getParcelableArrayExtra((NfcAdapter.EXTRA_NDEF_MESSAGES));
            parsed = ndef2React(ndef, messages);
        } else if (action.equals(NfcAdapter.ACTION_TECH_DISCOVERED)) {
            for (String tagTech : tag.getTechList()) {
                Log.d(LOG_TAG, tagTech);
                if (tagTech.equals("android.nfc.tech.MifareUltralight")) { //
                    Ndef ndef = Ndef.get(tag);
                    MifareUltralight mifare = MifareUltralight.get(tag);
                    JSONObject json = new JSONObject();
                    String message = "";
                    try {
                        mifare.connect();
                        String paddedMessage = new String(mifare.readPages(144));
                        message = paddedMessage.substring(5, paddedMessage.length() - 3);
                        try {
                            json.put("sigFox", message);
                        } catch (JSONException e) {
                            //Not sure why this would happen, documentation is unclear.
                            Log.e(LOG_TAG, "Failed to convert message to json: " + message, e);
                        }
                        Log.d(LOG_TAG, message);

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    parsed = ndef2React(ndef, new NdefMessage[] { ndef.getCachedNdefMessage() });
                    parsed.putString("sigfoxId", message);
                    Log.d(LOG_TAG, parsed.toString());
                    try {
                        mifare.close();
                    } catch (IOException e) {
                        Log.d(LOG_TAG, e.toString());
                    }
                }
            }
        } else if (action.equals(NfcAdapter.ACTION_TAG_DISCOVERED)) {
            parsed = tag2React(tag);
        }

        synchronized(this) {
            if (writeNdefRequest != null) {
                writeNdef(
                        tag,
                        writeNdefRequest.message,
                        writeNdefRequest.callback
                );
                writeNdefRequest = null;

                // explicitly return null, to avoid extra detection
                return parsed;
            }
        }

        return parsed;
    }

    private WritableMap json2React(JSONObject json) {
        try {
            return JsonConvert.jsonToReact(json);
        } catch (JSONException ex) {
            return null;
        }
    }

    private WritableMap tag2React(Tag tag) {
        try {
            JSONObject json = Util.tagToJSON(tag);
            return JsonConvert.jsonToReact(json);
        } catch (JSONException ex) {
            return null;
        }
    }

    private WritableMap ndef2React(Ndef ndef, Parcelable[] messages) {
        try {
            JSONObject json = buildNdefJSON(ndef, messages);
            return JsonConvert.jsonToReact(json);
        } catch (JSONException ex) {
            return null;
        }
    }

    JSONObject buildNdefJSON(Ndef ndef, Parcelable[] messages) {
        JSONObject json = Util.ndefToJSON(ndef);

        // ndef is null for peer-to-peer
        // ndef and messages are null for ndef format-able
        if (ndef == null && messages != null) {

            try {

                if (messages.length > 0) {
                    NdefMessage message = (NdefMessage) messages[0];
                    json.put("ndefMessage", Util.messageToJSON(message));
                    // guessing type, would prefer a more definitive way to determine type
                    json.put("type", "NDEF Push Protocol");
                }

                if (messages.length > 1) {
                    Log.d(LOG_TAG, "Expected one ndefMessage but found " + messages.length);
                }

            } catch (JSONException e) {
                // shouldn't happen
                Log.e(Util.TAG, "Failed to convert ndefMessage into json", e);
            }
        }
        return json;
    }

    private void writeNdef(Tag tag, NdefMessage message, Callback callback) {

        try {
            Log.d(LOG_TAG, "ready to writeNdef");
            Ndef ndef = Ndef.get(tag);
            if (ndef == null) {
                callback.invoke("fail to apply ndef tech");
            } else if (!ndef.isWritable()) {
                callback.invoke("tag is not writeable");
            } else if (ndef.getMaxSize() < message.toByteArray().length) {
                callback.invoke("tag size is not enough");
            } else {
                Log.d(LOG_TAG, "ready to writeNdef, seriously");
                ndef.connect();
                ndef.writeNdefMessage(message);
                callback.invoke(null, true);
            }
        } catch (Exception ex) {
            Log.d(LOG_TAG, "write exception!!");
            Log.d(LOG_TAG, ex.toString());
            callback.invoke(ex.getMessage());
        }
    }

    private byte[] rnArrayToBytes(ReadableArray rArray) {
        byte[] bytes = new byte[rArray.size()];
        for (int i = 0; i < rArray.size(); i++) {
            bytes[i] = (byte)(rArray.getInt(i) & 0xff);
        }
        return bytes;
    }

}

