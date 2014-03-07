package com.android.internal.telephony;

import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.SubInfoRecord;
import android.telephony.TelephonyManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneProxyManager;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccUtils;

import java.util.List;

/**
 *@hide
 */
public class SubInfoRecordUpdater extends Handler {
    private static final String LOG_TAG = "SUB";
    private static final int PROJECT_SIM_NUM = SystemProperties.getInt(TelephonyProperties.PROPERTY_SIM_COUNT, 1);
    private static final int EVENT_OFFSET = 8;
    private static final int EVENT_QUERY_ICCID_DONE = 1;
    private static final String ICCID_STRING_FOR_NO_SIM = "";
    private static final int ICCID_WAIT_TIMER = 90;

    /**
     *  int[] sInsertSimState maintains all slots' SIM inserted status currently,
     *  it may contain 4 kinds of values:
     *    SIM_NOT_INSERT : no SIM inserted in slot i now
     *    SIM_CHANGED    : a valid SIM insert in slot i and is different SIM from last time
     *                     it will later become SIM_NEW or SIM_REPOSITION during update procedure
     *    SIM_NOT_CHANGE : a valid SIM insert in slot i and is the same SIM as last time
     *    SIM_NEW        : a valid SIM insert in slot i and is a new SIM
     *    SIM_REPOSITION : a valid SIM insert in slot i and is inserted in different slot last time
     *    positive integer #: index to distinguish SIM cards with the same IccId
     */
    public static final int SIM_NOT_CHANGE = 0;
    public static final int SIM_CHANGED    = -1;
    public static final int SIM_NEW        = -2;
    public static final int SIM_REPOSITION = -3;
    public static final int SIM_NOT_INSERT = -99;

    public static final int STATUS_NO_SIM_INSERTED = 0x00;
    public static final int STATUS_SIM1_INSERTED = 0x01;
    public static final int STATUS_SIM2_INSERTED = 0x02;
    public static final int STATUS_SIM3_INSERTED = 0x04;
    public static final int STATUS_SIM4_INSERTED = 0x08;

    private static PhoneProxyManager sPhoneProxyMgr = null;
    private static Phone[] sPhone = new Phone[PROJECT_SIM_NUM];
    private static Context sContext = null;
    private static CommandsInterface[] sCi = new CommandsInterface[PROJECT_SIM_NUM];
    private static IccFileHandler[] sFh = new IccFileHandler[PROJECT_SIM_NUM];
    private static String sIccId[] = new String[PROJECT_SIM_NUM];
    private static int[] sInsertSimState = new int[PROJECT_SIM_NUM];
    private static TelephonyManager sTelephonyMgr = null;
    // To prevent repeatedly update flow every time receiver SIM_STATE_CHANGE
    private static boolean sNeedUpdate = true;
    private static boolean sIsWaitingUpdateSimInfo = true;

    public SubInfoRecordUpdater() {
        logd("Constructor invoked");
        sPhoneProxyMgr = PhoneFactory.getPhoneProxyManager();

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sPhone[i] = ((PhoneProxy)(sPhoneProxyMgr.getPhoneProxy(i))).getActivePhone();
            logd("sPhone[" + i + "]:" + sPhone[i]);
            sCi[i] = ((PhoneBase)sPhone[i]).mCi;
            sFh[i] = null;
            sIccId[i] = null;
        }
        sContext = ((PhoneBase)sPhone[0]).getContext();
        IntentFilter intentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        sContext.registerReceiver(sReceiver, intentFilter);
        postDelayed(mUpdateSimInfoByIccIdRunnable, ICCID_WAIT_TIMER*1000);
    }

    private static int encodeEventId(int event, int simId) {
        return event << (simId * EVENT_OFFSET);
    }

    private final BroadcastReceiver sReceiver = new  BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            logd("[Receiver]+");
            String action = intent.getAction();
            int simId;
            logd("Action: " + action);
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                simId = intent.getIntExtra(PhoneConstants.SIM_ID_KEY, 0);
                logd("simId: " + simId + " simStatus: " + simStatus);
                if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(simStatus)
                        || IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(simStatus)) {
                    if (sIccId[simId] != null && sIccId[simId].equals(ICCID_STRING_FOR_NO_SIM)) {
                        logd("SIM" + (simId + 1) + " hot plug in");
                        sIccId[simId] = null;
                        sNeedUpdate = true;
                    }
                    queryIccId(simId);
                } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)) {
                    queryIccId(simId);
                    if (sTelephonyMgr == null) {
                        sTelephonyMgr = TelephonyManager.from(sContext);
                    }
                    //setDisplayNameForNewSim(sTelephonyMgr.getSimOperatorName(simId), simId, SimInfoManager.SIM_SOURCE);
                } else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simStatus)) {
                    if (sIccId[simId] != null && !sIccId[simId].equals(ICCID_STRING_FOR_NO_SIM)) {
                        logd("SIM" + (simId + 1) + " hot plug out");
                        sNeedUpdate = true;
                    }
                    sFh[simId] = null;
                    sIccId[simId] = ICCID_STRING_FOR_NO_SIM;
                    if (isAllIccIdQueryDone() && sNeedUpdate) {
                        updateSimInfoByIccId();
                    }
                }
            }
            logd("[Receiver]-");
        }
    };

    private boolean isAllIccIdQueryDone() {
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sIccId[i] == null) {
                logd("Wait for SIM" + (i + 1) + " IccId");
                return false;
            }
        }
        logd("All IccIds query complete");

        return true;
    }

    private Runnable mUpdateSimInfoByIccIdRunnable = new Runnable() {
        public void run() {
            logd("Update SimInfo time out!");
            sIsWaitingUpdateSimInfo = false;
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (sIccId[i] == null) {
                    sIccId[i] = ICCID_STRING_FOR_NO_SIM;
                }
            }
            updateSimInfoByIccId();
        }
    };

    public static void setDisplayNameForNewSub(String newSubName, int subId, int newNameSource) {
        SubInfoRecord subInfo = SubscriptionManager.getSubInfoUsingSubId(sContext, subId);
        if (subInfo != null) {
            // overwrite SIM display name if it is not assigned by user
            int oldNameSource = subInfo.mNameSource;
            String oldSubName = subInfo.mDisplayName;
            logd("[setDisplayNameForNewSub] mSubInfoIdx = " + subInfo.mSubId + ", oldSimName = " + oldSubName 
                    + ", oldNameSource = " + oldNameSource + ", newSubName = " + newSubName + ", newNameSource = " + newNameSource);
            if (oldSubName == null || 
                (oldNameSource == SubscriptionManager.DEFAULT_SOURCE && newSubName != null) ||
                (oldNameSource == SubscriptionManager.SIM_SOURCE && newSubName != null && !newSubName.equals(oldSubName))) {
                SubscriptionManager.setDisplayName(sContext, newSubName, subInfo.mSubId, newNameSource);
            }
        } else {
            logd("SUB" + (subId + 1) + " SubInfo not created yet");
        }
    }

    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult)msg.obj;
        int msgNum = msg.what;
        int simId; 
        for (simId = PhoneConstants.SIM_ID_1; simId <= PhoneConstants.SIM_ID_4; simId++) {
            int pivot = 1 << (simId * EVENT_OFFSET);
            if (msgNum >= pivot) {
                continue;
            } else {
                break;
            }
        }
        simId--;
        int event = msgNum >> (simId * EVENT_OFFSET);
        switch (event) {
            case EVENT_QUERY_ICCID_DONE:
                logd("handleMessage : <EVENT_QUERY_ICCID_DONE> SIM" + (simId + 1));
                if (ar.exception == null) {
                    if (ar.result != null) {
                        byte[] data = (byte[])ar.result;
                        sIccId[simId] = IccUtils.bcdToString(data, 0, data.length);
                    } else {
                        logd("Null ar");
                        sIccId[simId] = ICCID_STRING_FOR_NO_SIM;
                    }
                } else {
                    sIccId[simId] = ICCID_STRING_FOR_NO_SIM;
                    logd("Query IccId fail: " + ar.exception);
                }
                logd("sIccId[" + simId + "] = " + sIccId[simId]);
                if (isAllIccIdQueryDone() && sNeedUpdate) {
                    updateSimInfoByIccId();
                }
                break;
            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    private void queryIccId(int simId) {
        if (sFh[simId] == null) {
            logd("Getting IccFileHandler");
            sFh[simId] = ((PhoneBase)sPhone[simId]).getIccFileHandler();
        }
        if (sFh[simId] != null) {
            if (sIccId[simId] == null) {
                logd("Querying IccId");
                sFh[simId].loadEFTransparent(IccConstants.EF_ICCID, obtainMessage(encodeEventId(EVENT_QUERY_ICCID_DONE, simId)));
            }
        } else {
            sIccId[simId] = ICCID_STRING_FOR_NO_SIM;
            logd("sFh[" + simId + "] is null, SIM not inserted");
        }
    }

    synchronized public void updateSimInfoByIccId() {
        logd("[updateSimInfoByIccId]+ Start");
        sNeedUpdate = false;
        if (sIsWaitingUpdateSimInfo) {
            logd("Remove mUpdateSimInfoByIccIdRunnable");
            sIsWaitingUpdateSimInfo = false;
            removeCallbacks(mUpdateSimInfoByIccIdRunnable);
        }

        SubscriptionManager.clearSubInfo();
        
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sInsertSimState[i] = SIM_NOT_CHANGE;
        }

        int insertedSimCount = PROJECT_SIM_NUM;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (ICCID_STRING_FOR_NO_SIM.equals(sIccId[i])) {
                insertedSimCount--;
                sInsertSimState[i] = SIM_NOT_INSERT;
            }
        }
        logd("insertedSimCount = " + insertedSimCount);

        int index = 0;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sInsertSimState[i] == SIM_NOT_INSERT) {
                continue;
            }
            index = 2;
            for (int j = i + 1; j < PROJECT_SIM_NUM; j++) {
                if (sInsertSimState[j] == SIM_NOT_CHANGE && sIccId[i].equals(sIccId[j])) {
                    sInsertSimState[i] = 1;
                    sInsertSimState[j] = index;
                    index++;
                }
            }
        }

        ContentResolver contentResolver = sContext.getContentResolver();
        String[] oldIccId = new String[PROJECT_SIM_NUM];
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            oldIccId[i] = null;
            List<SubInfoRecord> oldSubInfo = SubscriptionManager.getSubInfoUsingSimId(sContext, i);
            if (oldSubInfo != null) {
                oldIccId[i] = oldSubInfo.get(0).mIccId;
                logd("oldSubId = " + oldSubInfo.get(0).mSubId);
                if (sInsertSimState[i] == SIM_NOT_CHANGE && !sIccId[i].equals(oldIccId[i])) {
                    sInsertSimState[i] = SIM_CHANGED;
                }
                if (sInsertSimState[i] != SIM_NOT_CHANGE) {
                    ContentValues value = new ContentValues(1);
                    value.put(SubscriptionManager.SIM_ID, SubscriptionManager.SIM_NOT_INSERTED);
                    contentResolver.update(SubscriptionManager.CONTENT_URI, value,
                                                SubscriptionManager._ID + "=" + Long.toString(oldSubInfo.get(0).mSubId), null);
                }
            } else {
                if (sInsertSimState[i] == SIM_NOT_CHANGE) {
                    // no SIM inserted last time, but there is one SIM inserted now
                    sInsertSimState[i] = SIM_CHANGED;
                }
                oldIccId[i] = ICCID_STRING_FOR_NO_SIM;
                logd("No SIM in slot " + i + " last time");
            }
        }

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            logd("oldIccId[" + i + "] = " + oldIccId[i] + ", sIccId[" + i + "] = " + sIccId[i]);
        }

        //check if the inserted SIM is new SIM
        int nNewCardCount = 0;
        int nNewSimStatus = 0;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sInsertSimState[i] == SIM_NOT_INSERT) {
                logd("No SIM inserted in slot " + i + " this time");
            } else {
                if (sInsertSimState[i] > 0) {
                    //some special SIMs may have the same IccIds, add suffix to distinguish them
                    SubscriptionManager.addSubInfoRecord(sContext, sIccId[i] + Integer.toString(sInsertSimState[i]), i); 
                    logd("SUB" + (i + 1) + " has invalid IccId");
                } else /*if (sInsertSimState[i] != SIM_NOT_INSERT)*/ {
                    SubscriptionManager.addSubInfoRecord(sContext, sIccId[i], i);
                }
                if (isNewSim(sIccId[i], oldIccId)) {
                    nNewCardCount++;
                    switch (i) {
                        case PhoneConstants.SIM_ID_1:
                            nNewSimStatus |= STATUS_SIM1_INSERTED;
                            break;
                        case PhoneConstants.SIM_ID_2:
                            nNewSimStatus |= STATUS_SIM2_INSERTED;
                            break;
                        case PhoneConstants.SIM_ID_3:
                            nNewSimStatus |= STATUS_SIM3_INSERTED;
                            break;
                        case PhoneConstants.SIM_ID_4:
                            nNewSimStatus |= STATUS_SIM4_INSERTED;
                            break;
                    }

                    sInsertSimState[i] = SIM_NEW;
                }
            }
        }

        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sInsertSimState[i] == SIM_CHANGED) {
                sInsertSimState[i] = SIM_REPOSITION;
            }
            logd("sInsertSimState[" + i + "] = " + sInsertSimState[i]);
        }

        long[] subIdInSlot = {-3, -3, -3, -3};        
        List<SubInfoRecord> subInfos = SubscriptionManager.getActivatedSubInfoList(sContext);
        int nSubCount = (subInfos == null) ? 0 : subInfos.size();
        logd("nSubCount = " + nSubCount);
        for (int i=0; i<nSubCount; i++) {
            SubInfoRecord temp = subInfos.get(i);
            subIdInSlot[temp.mSimId] = temp.mSubId;
            logd("subIdInSlot[" + temp.mSimId + "] = " + temp.mSubId);
        }

        // true if any slot has no SIM this time, but has SIM last time
        boolean hasSimRemoved = false;
        for (int i=0; i < PROJECT_SIM_NUM; i++) {
            if (sIccId[i] != null && sIccId[i].equals(ICCID_STRING_FOR_NO_SIM) && !oldIccId[i].equals("")) {
                hasSimRemoved = true;
                break;
            }
        }

        if (nNewCardCount == 0) {
            int i;
            if (hasSimRemoved) {
                // no new SIM, at least one SIM is removed, check if any SIM is repositioned first
                for (i=0; i < PROJECT_SIM_NUM; i++) {
                    if (sInsertSimState[i] == SIM_REPOSITION) {
                        logd("No new SIM detected and SIM repositioned");
                        setUpdatedData(SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM, nSubCount, nNewSimStatus);
                        break;
                    }
                }
                if (i == PROJECT_SIM_NUM) {
                    // no new SIM, no SIM is repositioned => at least one SIM is removed
                    logd("No new SIM detected and SIM removed");
                    setUpdatedData(SubscriptionManager.EXTRA_VALUE_REMOVE_SIM, nSubCount, nNewSimStatus);
                }
            } else {
                // no SIM is removed, no new SIM, just check if any SIM is repositioned
                for (i=0; i< PROJECT_SIM_NUM; i++) {
                    if (sInsertSimState[i] == SIM_REPOSITION) {
                        logd("No new SIM detected and SIM repositioned");
                        setUpdatedData(SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM, nSubCount, nNewSimStatus);
                        break;
                    }
                }
                if (i == PROJECT_SIM_NUM) {
                    // all status remain unchanged
                    logd("[updateSimInfoByIccId] All SIM inserted into the same slot");
                    setUpdatedData(SubscriptionManager.EXTRA_VALUE_NOCHANGE, nSubCount, nNewSimStatus);
                }
            }
        } else {
            logd("New SIM detected");
            setUpdatedData(SubscriptionManager.EXTRA_VALUE_NEW_SIM, nSubCount, nNewSimStatus);
        }

        logd("[updateSimInfoByIccId]- SimInfo update complete");
    }

    private static void setUpdatedData(int detectedType, int subCount, int newSimStatus) {

        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);

        logd("[setUpdatedData]+ ");

        if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM ) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, SubscriptionManager.EXTRA_VALUE_NEW_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
            intent.putExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, newSimStatus);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_REMOVE_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, SubscriptionManager.EXTRA_VALUE_REMOVE_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, SubscriptionManager.EXTRA_VALUE_NOCHANGE);
        }

        logd("broadcast intent ACTION_SUBINFO_RECORD_UPDATED : [" + detectedType + ", " + subCount + ", " + newSimStatus+ "]");
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE, UserHandle.USER_ALL);
        logd("[setUpdatedData]- ");
    }

    private static boolean isNewSim(String iccId, String[] oldIccId) {
        boolean newSim = true;
        for(int i = 0; i < PROJECT_SIM_NUM; i++) {
            if(iccId.equals(oldIccId[i])) {
                newSim = false;
                break;
            }
        }
        logd("newSim = " + newSim);

        return newSim;
    }

    public void dispose() {
        logd("[dispose]");
        sContext.unregisterReceiver(sReceiver);
    }

    private static void logd(String message) {
        Rlog.d(LOG_TAG, "[SubInfoRecordUpdater]" + message);
    }
}

