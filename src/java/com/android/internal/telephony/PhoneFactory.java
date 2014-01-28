/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.ComponentName;
import android.content.Context;
import android.net.LocalServerSocket;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.sip.SipPhone;
import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.uicc.UiccController;

import com.android.internal.telephony.RILConstants.SimCardID;
import android.os.SystemProperties;


/**
 * {@hide}
 */
public class PhoneFactory {
    static final String LOG_TAG = "PhoneFactory";
    static final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static final int SOCKET_OPEN_MAX_RETRY = 3;

    //***** Class Variables

    private static boolean mIsDualMode = false;
    static private Phone sProxyPhone[] = {null, null};

    static public CommandsInterface sCommandsInterface[]= {null, null};

    static private boolean sMadeDefaults[] = {false, false};
    static private PhoneNotifier sPhoneNotifier[] = {null, null};
    static private Looper sLooper[] = {null, null};
    static private Context sContext;

    static final int preferredNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;

    //***** Class Methods

    public static void makeDefaultPhones(Context context) {
        makeDefaultPhones(context, false);
    }

    public static void makeDefaultPhones(Context context, boolean isDualMode) {
        mIsDualMode = isDualMode;
        int networkMode = Integer.valueOf(SystemProperties.get(TelephonyProperties.PROPERTY_NETWORK_TYPE, String.valueOf(Phone.NT_MODE_WCDMA_PREF)));
        Rlog.i(LOG_TAG, "SIM1 Network Mode set to " + Integer.toString(networkMode));
        makeDefaultPhone(context, SimCardID.ID_ZERO, networkMode);

        if (mIsDualMode) {
            int networkMode2 = Integer.valueOf(SystemProperties.get(TelephonyProperties.PROPERTY_NETWORK_TYPE + "_"+ String.valueOf(SimCardID.ID_ONE.toInt()), String.valueOf(Phone.NT_MODE_GSM_ONLY)));
            Rlog.i(LOG_TAG, "SIM2 Network Mode set to " + Integer.toString(networkMode2));
            makeDefaultPhone(context, SimCardID.ID_ONE, networkMode2);
        }
    }

    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    public static void makeDefaultPhone(Context context) {
        //Get preferredNetworkMode from Settings.System
        int networkMode = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
                Rlog.i(LOG_TAG, "Network Mode set to " + Integer.toString(networkMode));
        makeDefaultPhone(context, SimCardID.ID_ZERO, networkMode);
    }

    public static void makeDefaultPhone(Context context, SimCardID simCardId, int networkMode) {
        synchronized(Phone.class) {
            if (!sMadeDefaults[simCardId.toInt()]) {
                sLooper[simCardId.toInt()] = Looper.myLooper();
                sContext = context;

                if (sLooper[simCardId.toInt()] == null) {
                    throw new RuntimeException(
                        "PhoneFactory.makeDefaultPhone must be called from Looper thread");
                }

                String sockName = "com.android.internal.telephony"+String.valueOf(simCardId.toInt());
                Rlog.i(LOG_TAG, "sockName "+sockName);
                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket(sockName);
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    } else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }

                sPhoneNotifier[simCardId.toInt()] = new DefaultPhoneNotifier(simCardId);

                int cdmaSubscription = CdmaSubscriptionSourceManager.getDefault(context);
                Rlog.i(LOG_TAG, "Cdma Subscription set to " + cdmaSubscription);

                //reads the system properties and makes commandsinterface
                sCommandsInterface[simCardId.toInt()] = new RIL(context, networkMode, cdmaSubscription, simCardId);

                // Instantiate UiccController so that all other classes can just call getInstance()
                UiccController.make(context, sCommandsInterface[simCardId.toInt()], simCardId);

                int phoneType = TelephonyManager.getPhoneType(networkMode);
                if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    Rlog.i(LOG_TAG, "Creating GSMPhone");
                    sProxyPhone[simCardId.toInt()] = new PhoneProxy(new GSMPhone(context,
                            sCommandsInterface[simCardId.toInt()], sPhoneNotifier[simCardId.toInt()], simCardId));
                } else if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    switch (TelephonyManager.getLteOnCdmaModeStatic()) {
                        case PhoneConstants.LTE_ON_CDMA_TRUE:
                            Rlog.i(LOG_TAG, "Creating CDMALTEPhone");
                            sProxyPhone[simCardId.toInt()] = new PhoneProxy(new CDMALTEPhone(context,
                                sCommandsInterface[simCardId.toInt()], sPhoneNotifier[simCardId.toInt()], simCardId));
                            break;
                        case PhoneConstants.LTE_ON_CDMA_FALSE:
                        default:
                            Rlog.i(LOG_TAG, "Creating CDMAPhone");
                            sProxyPhone[simCardId.toInt()] = new PhoneProxy(new CDMAPhone(context,
                                    sCommandsInterface[simCardId.toInt()], sPhoneNotifier[simCardId.toInt()], simCardId));
                            break;
                    }
                }

                // Ensure that we have a default SMS app. Requesting the app with
                // updateIfNeeded set to true is enough to configure a default SMS app.
                ComponentName componentName =
                        SmsApplication.getDefaultSmsApplication(context, true /* updateIfNeeded */);
                String packageName = "NONE";
                if (componentName != null) {
                    packageName = componentName.getPackageName();
                }
                Rlog.i(LOG_TAG, "defaultSmsApplication: " + packageName);

                // Set up monitor to watch for changes to SMS packages
                SmsApplication.initSmsPackageMonitor(context);

                sMadeDefaults[simCardId.toInt()] = true;
            }
        }
    }

    public static Phone getDefaultPhone() {
       return getDefaultPhone(SimCardID.ID_ZERO);
    }

    public static Phone getDefaultPhone(SimCardID simCardId) {
        if (sLooper[simCardId.toInt()] != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }

        if (!sMadeDefaults[simCardId.toInt()]) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
       return sProxyPhone[simCardId.toInt()];
    }

    public static Phone[] getDefaultPhones() {
        if (sLooper[SimCardID.ID_ZERO.toInt()] != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }

        if (!sMadeDefaults[SimCardID.ID_ZERO.toInt()] && !sMadeDefaults[SimCardID.ID_ONE.toInt()]) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }
        return sProxyPhone;
    }

    public static int getNumPhones() {
        if (mIsDualMode) return 2;
        else return 1;
    }

    public static Phone getCdmaPhone() {
        Phone phone;
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            switch (TelephonyManager.getLteOnCdmaModeStatic()) {
                case PhoneConstants.LTE_ON_CDMA_TRUE: {
                    phone = new CDMALTEPhone(sContext, sCommandsInterface[SimCardID.ID_ZERO.toInt()], sPhoneNotifier[SimCardID.ID_ZERO.toInt()]);
                    break;
                }
                case PhoneConstants.LTE_ON_CDMA_FALSE:
                case PhoneConstants.LTE_ON_CDMA_UNKNOWN:
                default: {
                    phone = new CDMAPhone(sContext, sCommandsInterface[SimCardID.ID_ZERO.toInt()], sPhoneNotifier[SimCardID.ID_ZERO.toInt()]);
                    break;
                }
            }
        }
        return phone;
    }

    public static Phone getGsmPhone() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            Phone phone = new GSMPhone(sContext, sCommandsInterface[SimCardID.ID_ZERO.toInt()], sPhoneNotifier[SimCardID.ID_ZERO.toInt()]);
            return phone;
        }
    }

    /**
     * Makes a {@link SipPhone} object.
     * @param sipUri the local SIP URI the phone runs on
     * @return the {@code SipPhone} object or null if the SIP URI is not valid
     */
    public static SipPhone makeSipPhone(String sipUri) {
        return SipPhoneFactory.makePhone(sipUri, sContext, sPhoneNotifier[SimCardID.ID_ZERO.toInt()]);
    }
}
