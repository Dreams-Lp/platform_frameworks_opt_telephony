/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.uicc;

import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.SubscriptionManager;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.PersoSubState;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.IccCardStatus.PinState;
import com.android.internal.telephony.uicc.UiccController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_SIM_STATE;


/**
 * @Deprecated use {@link UiccController}.getUiccCard instead.
 *
 * The Phone App assumes that there is only one icc card, and one icc application
 * available at a time. Moreover, it assumes such object (represented with IccCard)
 * is available all the time (whether {@link RILConstants#RIL_REQUEST_GET_SIM_STATUS} returned
 * or not, whether card has desired application or not, whether there really is a card in the
 * slot or not).
 *
 * UiccController, however, can handle multiple instances of icc objects (multiple
 * {@link UiccCardApplication}, multiple {@link IccFileHandler}, multiple {@link IccRecords})
 * created and destroyed dynamically during phone operation.
 *
 * This class implements the IccCard interface that is always available (right after default
 * phone object is constructed) to expose the current (based on voice radio technology)
 * application on the uicc card, so that external apps won't break.
 */

public class IccCardProxy extends Handler implements IccCard {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "IccCardProxy";

    private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE = 1;
    private static final int EVENT_RADIO_ON = 2;
    private static final int EVENT_ICC_CHANGED = 3;
    private static final int EVENT_ICC_ABSENT = 4;
    private static final int EVENT_ICC_LOCKED = 5;
    private static final int EVENT_APP_READY = 6;
    private static final int EVENT_RECORDS_LOADED = 7;
    private static final int EVENT_IMSI_READY = 8;
    private static final int EVENT_NETWORK_LOCKED = 9;
    private static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 11;

    private static final int EVENT_ICC_RECORD_EVENTS = 500;
    private static final int EVENT_SUBSCRIPTION_ACTIVATED = 501;
    private static final int EVENT_SUBSCRIPTION_DEACTIVATED = 502;

    private Integer mCardIndex = null;
    private Subscription mSubscriptionData = null;

    private final Object mLock = new Object();
    private Context mContext;
    private CommandsInterface mCi;

    private RegistrantList mAbsentRegistrants = new RegistrantList();
    private RegistrantList mPinLockedRegistrants = new RegistrantList();
    private RegistrantList mNetworkLockedRegistrants = new RegistrantList();

    private int mCurrentAppType = UiccController.APP_FAM_3GPP; //default to 3gpp?
    private UiccController mUiccController = null;
    private UiccCard mUiccCard = null;
    private UiccCardApplication mUiccApplication = null;
    private IccRecords mIccRecords = null;
    private CdmaSubscriptionSourceManager mCdmaSSM = null;
    private boolean mRadioOn = false;
    private boolean mQuietMode = false; // when set to true IccCardProxy will not broadcast
                                        // ACTION_SIM_STATE_CHANGED intents
    private boolean mInitialized = false;
    private State mExternalState = State.UNKNOWN;

    public IccCardProxy(Context context, CommandsInterface ci) {
        log("Creating");
        mContext = context;
        mCi = ci;
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context,
                ci, this, EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        ci.registerForOn(this,EVENT_RADIO_ON, null);
        ci.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, null);
        setExternalState(State.NOT_READY);
    }

    public IccCardProxy(Context context, CommandsInterface ci, int cardIndex) {
        this(context, ci);

        mCardIndex = cardIndex;

        //TODO: Card index and subscription are same???
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        subMgr.registerForSubscriptionActivated(mCardIndex,
                this, EVENT_SUBSCRIPTION_ACTIVATED, null);
        subMgr.registerForSubscriptionDeactivated(mCardIndex,
                this, EVENT_SUBSCRIPTION_DEACTIVATED, null);

        resetProperties();
        setExternalState(State.NOT_READY, false);
    }

    public void dispose() {
        synchronized (mLock) {
            log("Disposing");
            //Cleanup icc references
            mUiccController.unregisterForIccChanged(this);
            mUiccController = null;
            mCi.unregisterForOn(this);
            mCi.unregisterForOffOrNotAvailable(this);
            mCdmaSSM.dispose(this);
        }
    }

    /*
     * The card application that the external world sees will be based on the
     * voice radio technology only!
     */
    public void setVoiceRadioTech(int radioTech) {
        synchronized (mLock) {
            if (DBG) {
                log("Setting radio tech " + ServiceState.rilRadioTechnologyToString(radioTech));
            }
            if (ServiceState.isGsm(radioTech)) {
                mCurrentAppType = UiccController.APP_FAM_3GPP;
            } else {
                mCurrentAppType = UiccController.APP_FAM_3GPP2;
            }
            updateQuietMode();
        }
    }

    /**
     * In case of 3gpp2 we need to find out if subscription used is coming from
     * NV in which case we shouldn't broadcast any sim states changes.
     */
    private void updateQuietMode() {
        synchronized (mLock) {
            boolean oldQuietMode = mQuietMode;
            boolean newQuietMode;
            int cdmaSource = Phone.CDMA_SUBSCRIPTION_UNKNOWN;
            boolean isLteOnCdmaMode = TelephonyManager.getLteOnCdmaModeStatic()
                    == PhoneConstants.LTE_ON_CDMA_TRUE;
            if (mCurrentAppType == UiccController.APP_FAM_3GPP) {
                newQuietMode = false;
                if (DBG) log("updateQuietMode: 3GPP subscription -> newQuietMode=" + newQuietMode);
            } else {
                if (isLteOnCdmaMode) {
                    log("updateQuietMode: is cdma/lte device, force IccCardProxy into 3gpp mode");
                    mCurrentAppType = UiccController.APP_FAM_3GPP;
                }
                cdmaSource = mCdmaSSM != null ?
                        mCdmaSSM.getCdmaSubscriptionSource() : Phone.CDMA_SUBSCRIPTION_UNKNOWN;

                newQuietMode = (cdmaSource == Phone.CDMA_SUBSCRIPTION_NV)
                        && (mCurrentAppType == UiccController.APP_FAM_3GPP2)
                        && !isLteOnCdmaMode;
            }

            if (mQuietMode == false && newQuietMode == true) {
                // Last thing to do before switching to quiet mode is
                // broadcast ICC_READY
                log("Switching to QuietMode.");
                setExternalState(State.READY);
                mQuietMode = newQuietMode;
            } else if (mQuietMode == true && newQuietMode == false) {
                if (DBG) {
                    log("updateQuietMode: Switching out from QuietMode."
                            + " Force broadcast of current state=" + mExternalState);
                }
                mQuietMode = newQuietMode;
                setExternalState(mExternalState, true);
            }
            if (DBG) {
                log("updateQuietMode: QuietMode is " + mQuietMode + " (app_type="
                    + mCurrentAppType + " isLteOnCdmaMode=" + isLteOnCdmaMode
                    + " cdmaSource=" + cdmaSource + ")");
            }
            mInitialized = true;
            sendMessage(obtainMessage(EVENT_ICC_CHANGED));
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                mRadioOn = false;
                break;
            case EVENT_RADIO_ON:
                mRadioOn = true;
                if (!mInitialized) {
                    updateQuietMode();
                }
                break;
            case EVENT_ICC_CHANGED:
                if (mInitialized) {
                    updateIccAvailability();
                }
                break;
            case EVENT_ICC_ABSENT:
                mAbsentRegistrants.notifyRegistrants();
                setExternalState(State.ABSENT);
                break;
            case EVENT_ICC_LOCKED:
                processLockedState();
                break;
            case EVENT_APP_READY:
                setExternalState(State.READY);
                break;
            case EVENT_RECORDS_LOADED:
                if (mIccRecords != null) {
                    String operator = mIccRecords.getOperatorNumeric();
                    int sub = mCardIndex;

                    log("operator = " + operator + " SUB = " + sub);

                    if (operator != null) {
                        TelephonyManager.setTelephonyProperty(
                                PROPERTY_ICC_OPERATOR_NUMERIC, sub, operator);
                        String countryCode = operator.substring(0,3);
                        if (countryCode != null) {
                            TelephonyManager.setTelephonyProperty(
                                    PROPERTY_ICC_OPERATOR_ISO_COUNTRY, sub,
                                    MccTable.countryCodeForMcc(Integer.parseInt(countryCode)));
                        } else {
                            loge("EVENT_RECORDS_LOADED Country code is null");
                        }

                        // Update MCC MNC device configuration information only for default sub.
                        if (sub == TelephonyManager.getDefaultSubscription()) {
                            log("Update mccmnc config for default subscription.");
                            MccTable.updateMccMncConfiguration(mContext, operator);
                        }
                    } else {
                        loge("EVENT_RECORDS_LOADED Operator name is null");
                    }
                }
                broadcastIccStateChangedIntent(IccCardConstants.INTENT_VALUE_ICC_LOADED, null);
                break;
            case EVENT_IMSI_READY:
                broadcastIccStateChangedIntent(IccCardConstants.INTENT_VALUE_ICC_IMSI, null);
                break;
            case EVENT_NETWORK_LOCKED:
                mNetworkLockedRegistrants.notifyRegistrants();
                setExternalState(State.NETWORK_LOCKED);
                break;
            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                updateQuietMode();
                break;
            case EVENT_SUBSCRIPTION_ACTIVATED:
                log("EVENT_SUBSCRIPTION_ACTIVATED");
                onSubscriptionActivated();
                break;

            case EVENT_SUBSCRIPTION_DEACTIVATED:
                log("EVENT_SUBSCRIPTION_DEACTIVATED");
                onSubscriptionDeactivated();
                break;

            case EVENT_ICC_RECORD_EVENTS:
                if ((mCurrentAppType == UiccController.APP_FAM_3GPP) && (mIccRecords != null)) {
                    int sub = mCardIndex;
                    AsyncResult ar = (AsyncResult)msg.obj;
                    int eventCode = (Integer) ar.result;
                    if (eventCode == SIMRecords.EVENT_SPN) {
                        TelephonyManager.setTelephonyProperty(
                                PROPERTY_ICC_OPERATOR_ALPHA, sub,
                                mIccRecords.getServiceProviderName());
                    }
                }
                break;

            default:
                loge("Unhandled message with number: " + msg.what);
                break;
        }
    }

    private void onSubscriptionActivated() {
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        mSubscriptionData = subMgr.getCurrentSubscription(mCardIndex);

        updateIccAvailability();
        updateStateProperty();
    }

    private void onSubscriptionDeactivated() {
        resetProperties();
        mSubscriptionData = null;
        updateIccAvailability();
        updateStateProperty();
    }


    private void updateIccAvailability() {
        synchronized (mLock) {
            UiccCard newCard = ((UiccController) mUiccController).getUiccCard(mCardIndex);
            CardState state = CardState.CARDSTATE_ABSENT;
            UiccCardApplication newApp = null;
            IccRecords newRecords = null;
            if (newCard != null) {
                state = newCard.getCardState();
                newApp = newCard.getApplication(mCurrentAppType);
                if (newApp != null) {
                    newRecords = newApp.getIccRecords();
                }
            }

            if (mIccRecords != newRecords || mUiccApplication != newApp || mUiccCard != newCard) {
                if (DBG) log("Icc changed. Reregestering.");
                unregisterUiccCardEvents();
                mUiccCard = newCard;
                mUiccApplication = newApp;
                mIccRecords = newRecords;
                registerUiccCardEvents();
            }

            updateExternalState();
        }
    }

    void resetProperties() {
        if (mCurrentAppType == UiccController.APP_FAM_3GPP) {
            TelephonyManager.setTelephonyProperty(
                    PROPERTY_ICC_OPERATOR_NUMERIC, mCardIndex, "");
            TelephonyManager.setTelephonyProperty(
                    PROPERTY_ICC_OPERATOR_ISO_COUNTRY, mCardIndex, "");
            TelephonyManager.setTelephonyProperty(
                    PROPERTY_ICC_OPERATOR_ALPHA, mCardIndex, "");
         }
    }

    private void HandleDetectedState() {
    // CAF_MSIM SAND
//        setExternalState(State.DETECTED, false);
    }

    private void updateExternalState() {
        if (mUiccCard == null || mUiccCard.getCardState() == CardState.CARDSTATE_ABSENT) {
            if (mRadioOn) {
                setExternalState(State.ABSENT);
            } else {
                setExternalState(State.NOT_READY);
            }
            return;
        }

        if (mUiccCard.getCardState() == CardState.CARDSTATE_ERROR ||
                mUiccApplication == null) {
            setExternalState(State.UNKNOWN);
            return;
        }

        switch (mUiccApplication.getState()) {
            case APPSTATE_UNKNOWN:
                setExternalState(State.UNKNOWN);
                break;
            case APPSTATE_DETECTED:
                HandleDetectedState();
                break;
            case APPSTATE_PIN:
                setExternalState(State.PIN_REQUIRED);
                break;
            case APPSTATE_PUK:
                setExternalState(State.PUK_REQUIRED);
                break;
            case APPSTATE_SUBSCRIPTION_PERSO:
                if (mUiccApplication.getPersoSubState() ==
                        PersoSubState.PERSOSUBSTATE_SIM_NETWORK) {
                    setExternalState(State.NETWORK_LOCKED);
                } else {
                    setExternalState(State.UNKNOWN);
                }
                break;
            case APPSTATE_READY:
                setExternalState(State.READY);
                break;
        }
    }

    private void registerUiccCardEvents() {
        if (mUiccCard != null) mUiccCard.registerForAbsent(this, EVENT_ICC_ABSENT, null);
        if (mUiccApplication != null) {
            mUiccApplication.registerForReady(this, EVENT_APP_READY, null);
            mUiccApplication.registerForLocked(this, EVENT_ICC_LOCKED, null);
            mUiccApplication.registerForNetworkLocked(this, EVENT_NETWORK_LOCKED, null);
        }
        if (mIccRecords != null) {
            mIccRecords.registerForImsiReady(this, EVENT_IMSI_READY, null);
            mIccRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
        }

        if (mIccRecords != null) {
            mIccRecords.registerForRecordsEvents(this, EVENT_ICC_RECORD_EVENTS, null);
        }
    }

    private void unregisterUiccCardEvents() {
        if (mUiccCard != null) mUiccCard.unregisterForAbsent(this);
        if (mUiccApplication != null) mUiccApplication.unregisterForReady(this);
        if (mUiccApplication != null) mUiccApplication.unregisterForLocked(this);
        if (mUiccApplication != null) mUiccApplication.unregisterForNetworkLocked(this);
        if (mIccRecords != null) mIccRecords.unregisterForImsiReady(this);
        if (mIccRecords != null) mIccRecords.unregisterForRecordsLoaded(this);
        if (mIccRecords != null) mIccRecords.unregisterForRecordsEvents(this);
    }

    private void updateStateProperty() {
        TelephonyManager.setTelephonyProperty(PROPERTY_SIM_STATE,
                SubscriptionManager.getInstance().getSlotId(mCardIndex),getState().toString());
    }

    private void broadcastIccStateChangedIntent(String value, String reason) {
        synchronized (mLock) {
            if (mCardIndex == null) {
                loge("broadcastIccStateChangedIntent: Card Index is not set; Return!!");
                return;
            }

            int subId = mCardIndex;
            if (mQuietMode) {
                log("QuietMode: NOT Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                        + " reason " + reason);
                return;
            }

            Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(PhoneConstants.PHONE_NAME_KEY, "Phone");
            intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE, value);
            intent.putExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON, reason);

            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
            log("Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                + " reason " + reason + " for subscription : " + subId);
            ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE,
                    UserHandle.USER_ALL);
        }
    }

    private void setExternalState(State newState, boolean override) {
        synchronized (mLock) {
            if (mCardIndex == null) {
                loge("setExternalState: Card Index is not set; Return!!");
                return;
            }

            if (!override && newState == mExternalState) {
                return;
            }
            mExternalState = newState;
            TelephonyManager.setTelephonyProperty(PROPERTY_SIM_STATE,
                    mCardIndex, getState().toString());
            broadcastIccStateChangedIntent(getIccStateIntentString(mExternalState),
                    getIccStateReason(mExternalState));
            // TODO: Need to notify registrants for other states as well.
            if ( State.ABSENT == mExternalState) {
                mAbsentRegistrants.notifyRegistrants();
            }
        }
    }

    private void processLockedState() {
        synchronized (mLock) {
            if (mUiccApplication == null) {
                //Don't need to do anything if non-existent application is locked
                return;
            }
            PinState pin1State = mUiccApplication.getPin1State();
            if (pin1State == PinState.PINSTATE_ENABLED_PERM_BLOCKED) {
                setExternalState(State.PERM_DISABLED);
                return;
            }

            AppState appState = mUiccApplication.getState();
            switch (appState) {
                case APPSTATE_PIN:
                    mPinLockedRegistrants.notifyRegistrants();
                    setExternalState(State.PIN_REQUIRED);
                    break;
                case APPSTATE_PUK:
                    setExternalState(State.PUK_REQUIRED);
                    break;
                case APPSTATE_DETECTED:
                case APPSTATE_READY:
                case APPSTATE_SUBSCRIPTION_PERSO:
                case APPSTATE_UNKNOWN:
                    // Neither required
                    break;
            }
        }
    }

    private void setExternalState(State newState) {
        setExternalState(newState, false);
    }

    public boolean getIccRecordsLoaded() {
        synchronized (mLock) {
            if (mIccRecords != null) {
                return mIccRecords.getRecordsLoaded();
            }
            return false;
        }
    }

    private String getIccStateIntentString(State state) {
        switch (state) {
            case ABSENT: return IccCardConstants.INTENT_VALUE_ICC_ABSENT;
            case PIN_REQUIRED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case PUK_REQUIRED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case NETWORK_LOCKED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case READY: return IccCardConstants.INTENT_VALUE_ICC_READY;
            case NOT_READY: return IccCardConstants.INTENT_VALUE_ICC_NOT_READY;
            case PERM_DISABLED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            default: return IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;
        }
    }

    /**
     * Locked state have a reason (PIN, PUK, NETWORK, PERM_DISABLED)
     * @return reason
     */
    private String getIccStateReason(State state) {
        switch (state) {
            case PIN_REQUIRED: return IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN;
            case PUK_REQUIRED: return IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK;
            case NETWORK_LOCKED: return IccCardConstants.INTENT_VALUE_LOCKED_NETWORK;
            case PERM_DISABLED: return IccCardConstants.INTENT_VALUE_ABSENT_ON_PERM_DISABLED;
            default: return null;
       }
    }

    /* IccCard interface implementation */
    @Override
    public State getState() {
        synchronized (mLock) {
            return mExternalState;
        }
    }

    @Override
    public IccRecords getIccRecords() {
        synchronized (mLock) {
            return mIccRecords;
        }
    }

    @Override
    public IccFileHandler getIccFileHandler() {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                return mUiccApplication.getIccFileHandler();
            }
            return null;
        }
    }

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    @Override
    public void registerForAbsent(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);

            mAbsentRegistrants.add(r);

            if (getState() == State.ABSENT) {
                r.notifyRegistrant();
            }
        }
    }

    @Override
    public void unregisterForAbsent(Handler h) {
        synchronized (mLock) {
            mAbsentRegistrants.remove(h);
        }
    }

    /**
     * Notifies handler of any transition into State.NETWORK_LOCKED
     */
    @Override
    public void registerForNetworkLocked(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);

            mNetworkLockedRegistrants.add(r);

            if (getState() == State.NETWORK_LOCKED) {
                r.notifyRegistrant();
            }
        }
    }

    @Override
    public void unregisterForNetworkLocked(Handler h) {
        synchronized (mLock) {
            mNetworkLockedRegistrants.remove(h);
        }
    }

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    @Override
    public void registerForLocked(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);

            mPinLockedRegistrants.add(r);

            if (getState().isPinLocked()) {
                r.notifyRegistrant();
            }
        }
    }

    @Override
    public void unregisterForLocked(Handler h) {
        synchronized (mLock) {
            mPinLockedRegistrants.remove(h);
        }
    }

    @Override
    public void supplyPin(String pin, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.supplyPin(pin, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void supplyPuk(String puk, String newPin, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.supplyPuk(puk, newPin, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void supplyPin2(String pin2, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.supplyPin2(pin2, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void supplyPuk2(String puk2, String newPin2, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.supplyPuk2(puk2, newPin2, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void supplyNetworkDepersonalization(String pin, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.supplyNetworkDepersonalization(pin, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("CommandsInterface is not set.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public boolean getIccLockEnabled() {
        synchronized (mLock) {
            /* defaults to false, if ICC is absent/deactivated */
            Boolean retValue = mUiccApplication != null ?
                    mUiccApplication.getIccLockEnabled() : false;
            return retValue;
        }
    }

    @Override
    public boolean getIccFdnEnabled() {
        synchronized (mLock) {
            Boolean retValue = mUiccApplication != null ?
                    mUiccApplication.getIccFdnEnabled() : false;
            return retValue;
        }
    }

    public boolean getIccFdnAvailable() {
        boolean retValue = mUiccApplication != null ? mUiccApplication.getIccFdnAvailable() : false;
        return retValue;
    }

    public boolean getIccPin2Blocked() {
        /* defaults to disabled */
        Boolean retValue = mUiccApplication != null ? mUiccApplication.getIccPin2Blocked() : false;
        return retValue;
    }

    public boolean getIccPuk2Blocked() {
        /* defaults to disabled */
        Boolean retValue = mUiccApplication != null ? mUiccApplication.getIccPuk2Blocked() : false;
        return retValue;
    }

    @Override
    public void setIccLockEnabled(boolean enabled, String password, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.setIccLockEnabled(enabled, password, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void setIccFdnEnabled(boolean enabled, String password, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.setIccFdnEnabled(enabled, password, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void changeIccLockPassword(String oldPassword, String newPassword, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.changeIccLockPassword(oldPassword, newPassword, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public void changeIccFdnPassword(String oldPassword, String newPassword, Message onComplete) {
        synchronized (mLock) {
            if (mUiccApplication != null) {
                mUiccApplication.changeIccFdnPassword(oldPassword, newPassword, onComplete);
            } else if (onComplete != null) {
                Exception e = new RuntimeException("ICC card is absent.");
                AsyncResult.forMessage(onComplete).exception = e;
                onComplete.sendToTarget();
                return;
            }
        }
    }

    @Override
    public String getServiceProviderName() {
        synchronized (mLock) {
            if (mIccRecords != null) {
                return mIccRecords.getServiceProviderName();
            }
            return null;
        }
    }

    @Override
    public boolean isApplicationOnIcc(IccCardApplicationStatus.AppType type) {
        synchronized (mLock) {
            Boolean retValue = mUiccCard != null ? mUiccCard.isApplicationOnIcc(type) : false;
            return retValue;
        }
    }

    @Override
    public boolean hasIccCard() {
        synchronized (mLock) {
            if (mUiccCard != null && mUiccCard.getCardState() != CardState.CARDSTATE_ABSENT) {
                return true;
            }
            return false;
        }
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("IccCardProxy: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mCi=" + mCi);
        pw.println(" mAbsentRegistrants: size=" + mAbsentRegistrants.size());
        for (int i = 0; i < mAbsentRegistrants.size(); i++) {
            pw.println("  mAbsentRegistrants[" + i + "]="
                    + ((Registrant)mAbsentRegistrants.get(i)).getHandler());
        }
        pw.println(" mPinLockedRegistrants: size=" + mPinLockedRegistrants.size());
        for (int i = 0; i < mPinLockedRegistrants.size(); i++) {
            pw.println("  mPinLockedRegistrants[" + i + "]="
                    + ((Registrant)mPinLockedRegistrants.get(i)).getHandler());
        }
        pw.println(" mNetworkLockedRegistrants: size=" + mNetworkLockedRegistrants.size());
        for (int i = 0; i < mNetworkLockedRegistrants.size(); i++) {
            pw.println("  mNetworkLockedRegistrants[" + i + "]="
                    + ((Registrant)mNetworkLockedRegistrants.get(i)).getHandler());
        }
        pw.println(" mCurrentAppType=" + mCurrentAppType);
        pw.println(" mUiccController=" + mUiccController);
        pw.println(" mUiccCard=" + mUiccCard);
        pw.println(" mUiccApplication=" + mUiccApplication);
        pw.println(" mIccRecords=" + mIccRecords);
        pw.println(" mCdmaSSM=" + mCdmaSSM);
        pw.println(" mRadioOn=" + mRadioOn);
        pw.println(" mQuietMode=" + mQuietMode);
        pw.println(" mInitialized=" + mInitialized);
        pw.println(" mExternalState=" + mExternalState);

        pw.flush();
    }
}
