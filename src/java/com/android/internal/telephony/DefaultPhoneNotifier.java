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

import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.CellInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.Rlog;

import com.android.internal.telephony.ITelephonyRegistry;

import java.util.List;

/**
 * broadcast intents
 */
public class DefaultPhoneNotifier implements PhoneNotifier {

    static final String LOG_TAG = "GSM";
    private static final boolean DBG = true;
    private ITelephonyRegistry mRegistry;

    /*package*/
    DefaultPhoneNotifier() {
        mRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
    }

    public void notifyPhoneState(Phone sender) {
        Call ringingCall = sender.getRingingCall();
        String incomingNumber = "";
        if (ringingCall != null && ringingCall.getEarliestConnection() != null){
            incomingNumber = ringingCall.getEarliestConnection().getAddress();
        }
        try {
            mRegistry.notifyCallState(convertCallState(sender.getState()), incomingNumber);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyServiceState(Phone sender) {
        ServiceState ss = sender.getServiceState();
        if (ss == null) {
            ss = new ServiceState();
            ss.setStateOutOfService();
        }
        try {
            mRegistry.notifyServiceState(ss);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifySignalStrength(Phone sender) {
        try {
            mRegistry.notifySignalStrength(sender.getSignalStrength());
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyMessageWaitingChanged(Phone sender) {
        try {
            mRegistry.notifyMessageWaitingChanged(sender.getMessageWaitingIndicator());
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyCallForwardingChanged(Phone sender) {
        try {
            mRegistry.notifyCallForwardingChanged(sender.getCallForwardingIndicator());
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyDataActivity(Phone sender) {
        try {
            mRegistry.notifyDataActivity(convertDataActivityState(sender.getDataActivityState()));
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        doNotifyDataConnection(sender, reason, apnType, state);
    }

    private void doNotifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        // TODO
        // use apnType as the key to which connection we're talking about.
        // pass apnType back up to fetch particular for this one.
        TelephonyManager telephony = TelephonyManager.getDefault();
        LinkProperties linkProperties = null;
        LinkCapabilities linkCapabilities = null;
        boolean roaming = false;

        if (state == PhoneConstants.DataState.CONNECTED) {
            linkProperties = sender.getLinkProperties(apnType);
            linkCapabilities = sender.getLinkCapabilities(apnType);
        }
        ServiceState ss = sender.getServiceState();
        if (ss != null) roaming = ss.getRoaming();

        try {
            mRegistry.notifyDataConnection(
                    convertDataState(state),
                    sender.isDataConnectivityPossible(apnType), reason,
                    sender.getActiveApnHost(apnType),
                    apnType,
                    linkProperties,
                    linkCapabilities,
                    ((telephony!=null) ? telephony.getNetworkType() :
                    TelephonyManager.NETWORK_TYPE_UNKNOWN),
                    roaming);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyDataConnectionFailed(Phone sender, String reason, String apnType) {
        try {
            mRegistry.notifyDataConnectionFailed(reason, apnType);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyCellLocation(Phone sender) {
        Bundle data = new Bundle();
        sender.getCellLocation().fillInNotifierBundle(data);
        try {
            mRegistry.notifyCellLocation(data);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyCellInfo(Phone sender, List<CellInfo> cellInfo) {
        try {
            mRegistry.notifyCellInfo(cellInfo);
        } catch (RemoteException ex) {

        }
    }

    public void notifyOtaspChanged(Phone sender, int otaspMode) {
        try {
            mRegistry.notifyOtaspChanged(otaspMode);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyPreciseCallState(Phone sender) {
        Call ringingCall = sender.getRingingCall();
        Call foregroundCall = sender.getForegroundCall();
        Call backgroundCall = sender.getBackgroundCall();
        if (ringingCall != null && foregroundCall != null && backgroundCall != null) {
            try {
                mRegistry.notifyPreciseCallState(
                        convertPreciseCallState(ringingCall.getState()),
                        convertPreciseCallState(foregroundCall.getState()),
                        convertPreciseCallState(backgroundCall.getState()));
            } catch (RemoteException ex) {
                // system process is dead
            }
        }
    }

    public void notifyDisconnectCause(Connection.DisconnectCause cause) {
        try {
            mRegistry.notifyDisconnectCause(convertDisconnectCause(cause));
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyPreciseDisconnectCause(int preciseCause) {
        try {
            mRegistry.notifyPreciseDisconnectCause(preciseCause);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyPreciseDataConnectionFailed(Phone sender, String reason, String apnType, String apn, String failCause) {
        try {
            mRegistry.notifyPreciseDataConnectionFailed(reason, apnType, apn, failCause);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, "[PhoneNotifier] " + s);
    }

    /**
     * Convert the {@link State} enum into the TelephonyManager.CALL_STATE_* constants
     * for the public API.
     */
    public static int convertCallState(PhoneConstants.State state) {
        switch (state) {
            case RINGING:
                return TelephonyManager.CALL_STATE_RINGING;
            case OFFHOOK:
                return TelephonyManager.CALL_STATE_OFFHOOK;
            default:
                return TelephonyManager.CALL_STATE_IDLE;
        }
    }

    /**
     * Convert the TelephonyManager.CALL_STATE_* constants into the {@link State} enum
     * for the public API.
     */
    public static PhoneConstants.State convertCallState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                return PhoneConstants.State.RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return PhoneConstants.State.OFFHOOK;
            default:
                return PhoneConstants.State.IDLE;
        }
    }

    /**
     * Convert the {@link DataState} enum into the TelephonyManager.DATA_* constants
     * for the public API.
     */
    public static int convertDataState(PhoneConstants.DataState state) {
        switch (state) {
            case CONNECTING:
                return TelephonyManager.DATA_CONNECTING;
            case CONNECTED:
                return TelephonyManager.DATA_CONNECTED;
            case SUSPENDED:
                return TelephonyManager.DATA_SUSPENDED;
            default:
                return TelephonyManager.DATA_DISCONNECTED;
        }
    }

    /**
     * Convert the TelephonyManager.DATA_* constants into {@link DataState} enum
     * for the public API.
     */
    public static PhoneConstants.DataState convertDataState(int state) {
        switch (state) {
            case TelephonyManager.DATA_CONNECTING:
                return PhoneConstants.DataState.CONNECTING;
            case TelephonyManager.DATA_CONNECTED:
                return PhoneConstants.DataState.CONNECTED;
            case TelephonyManager.DATA_SUSPENDED:
                return PhoneConstants.DataState.SUSPENDED;
            default:
                return PhoneConstants.DataState.DISCONNECTED;
        }
    }

    /**
     * Convert the {@link DataState} enum into the TelephonyManager.DATA_* constants
     * for the public API.
     */
    public static int convertDataActivityState(Phone.DataActivityState state) {
        switch (state) {
            case DATAIN:
                return TelephonyManager.DATA_ACTIVITY_IN;
            case DATAOUT:
                return TelephonyManager.DATA_ACTIVITY_OUT;
            case DATAINANDOUT:
                return TelephonyManager.DATA_ACTIVITY_INOUT;
            case DORMANT:
                return TelephonyManager.DATA_ACTIVITY_DORMANT;
            default:
                return TelephonyManager.DATA_ACTIVITY_NONE;
        }
    }

    /**
     * Convert the TelephonyManager.DATA_* constants into the {@link DataState} enum
     * for the public API.
     */
    public static Phone.DataActivityState convertDataActivityState(int state) {
        switch (state) {
            case TelephonyManager.DATA_ACTIVITY_IN:
                return Phone.DataActivityState.DATAIN;
            case TelephonyManager.DATA_ACTIVITY_OUT:
                return Phone.DataActivityState.DATAOUT;
            case TelephonyManager.DATA_ACTIVITY_INOUT:
                return Phone.DataActivityState.DATAINANDOUT;
            case TelephonyManager.DATA_ACTIVITY_DORMANT:
                return Phone.DataActivityState.DORMANT;
            default:
                return Phone.DataActivityState.NONE;
        }
    }

    /**
     * Convert the {@link State} enum into the TelephonyManager.PRECISE_CALL_STATE_* constants
     * for the public API.
     */
    public static int convertPreciseCallState(Call.State state) {
        switch (state) {
            case ACTIVE:
                return TelephonyManager.PRECISE_CALL_STATE_ACTIVE;
            case HOLDING:
                return TelephonyManager.PRECISE_CALL_STATE_HOLDING;
            case DIALING:
                return TelephonyManager.PRECISE_CALL_STATE_DIALING;
            case ALERTING:
                return TelephonyManager.PRECISE_CALL_STATE_ALERTING;
            case INCOMING:
                return TelephonyManager.PRECISE_CALL_STATE_INCOMING;
            case WAITING:
                return TelephonyManager.PRECISE_CALL_STATE_WAITING;
            case DISCONNECTED:
                return TelephonyManager.PRECISE_CALL_STATE_DISCONNECTED;
            case DISCONNECTING:
                return TelephonyManager.PRECISE_CALL_STATE_DISCONNECTING;
            default:
                return TelephonyManager.PRECISE_CALL_STATE_IDLE;
        }
    }

    /**
     * Convert the Call.State.* constants into the {@link State} enum
     * for the public API.
     */
    public static Call.State convertPreciseCallState(int state) {
        switch (state) {
            case TelephonyManager.PRECISE_CALL_STATE_ACTIVE:
                return Call.State.ACTIVE;
            case TelephonyManager.PRECISE_CALL_STATE_HOLDING:
                return Call.State.HOLDING;
            case TelephonyManager.PRECISE_CALL_STATE_DIALING:
                return Call.State.DIALING;
            case TelephonyManager.PRECISE_CALL_STATE_ALERTING:
                return Call.State.ALERTING;
            case TelephonyManager.PRECISE_CALL_STATE_INCOMING:
                return Call.State.INCOMING;
            case TelephonyManager.PRECISE_CALL_STATE_WAITING:
                return Call.State.WAITING;
            case TelephonyManager.PRECISE_CALL_STATE_DISCONNECTED:
                return Call.State.DISCONNECTED;
            case TelephonyManager.PRECISE_CALL_STATE_DISCONNECTING:
                return Call.State.DISCONNECTING;
            default:
                return Call.State.IDLE;
        }
    }

    /**
     * Convert the {@link DisconnectCause} enum into the TelephonyManager.DISCONNECT_CAUSE_* constants
     * for the public API.
     */
    public static int convertDisconnectCause(Connection.DisconnectCause cause) {
        switch (cause) {
            case NOT_DISCONNECTED:
                return TelephonyManager.DISCONNECT_CAUSE_NOT_DISCONNECTED;
            case INCOMING_MISSED:
                return TelephonyManager.DISCONNECT_CAUSE_INCOMING_MISSED;
            case NORMAL:
                return TelephonyManager.DISCONNECT_CAUSE_NORMAL;
            case LOCAL:
                return TelephonyManager.DISCONNECT_CAUSE_LOCAL;
            case BUSY:
                return TelephonyManager.DISCONNECT_CAUSE_BUSY;
            case CONGESTION:
                return TelephonyManager.DISCONNECT_CAUSE_CONGESTION;
            case MMI:
                return TelephonyManager.DISCONNECT_CAUSE_MMI;
            case INVALID_NUMBER:
                return TelephonyManager.DISCONNECT_CAUSE_INVALID_NUMBER;
            case NUMBER_UNREACHABLE:
                return TelephonyManager.DISCONNECT_CAUSE_NUMBER_UNREACHABLE;
            case SERVER_UNREACHABLE:
                return TelephonyManager.DISCONNECT_CAUSE_SERVER_UNREACHABLE;
            case INVALID_CREDENTIALS:
                return TelephonyManager.DISCONNECT_CAUSE_INVALID_CREDENTIALS;
            case OUT_OF_NETWORK:
                return TelephonyManager.DISCONNECT_CAUSE_OUT_OF_NETWORK;
            case SERVER_ERROR:
                return TelephonyManager.DISCONNECT_CAUSE_SERVER_ERROR;
            case TIMED_OUT:
                return TelephonyManager.DISCONNECT_CAUSE_TIMED_OUT;
            case LOST_SIGNAL:
                return TelephonyManager.DISCONNECT_CAUSE_LOST_SIGNAL;
            case LIMIT_EXCEEDED:
                return TelephonyManager.DISCONNECT_CAUSE_LIMIT_EXCEEDED;
            case INCOMING_REJECTED:
                return TelephonyManager.DISCONNECT_CAUSE_INCOMING_REJECTED;
            case POWER_OFF:
                return TelephonyManager.DISCONNECT_CAUSE_POWER_OFF;
            case OUT_OF_SERVICE:
                return TelephonyManager.DISCONNECT_CAUSE_OUT_OF_SERVICE;
            case ICC_ERROR:
                return TelephonyManager.DISCONNECT_CAUSE_ICC_ERROR;
            case CALL_BARRED:
                return TelephonyManager.DISCONNECT_CAUSE_CALL_BARRED;
            case FDN_BLOCKED:
                return TelephonyManager.DISCONNECT_CAUSE_FDN_BLOCKED;
            case CS_RESTRICTED:
                return TelephonyManager.DISCONNECT_CAUSE_CS_RESTRICTED;
            case CS_RESTRICTED_NORMAL:
                return TelephonyManager.DISCONNECT_CAUSE_CS_RESTRICTED_NORMAL;
            case CS_RESTRICTED_EMERGENCY:
                return TelephonyManager.DISCONNECT_CAUSE_CS_RESTRICTED_EMERGENCY;
            case UNOBTAINABLE_NUMBER:
                return TelephonyManager.DISCONNECT_CAUSE_UNOBTAINABLE_NUMBER;
            case CDMA_LOCKED_UNTIL_POWER_CYCLE:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_LOCKED_UNTIL_POWER_CYCLE;
            case CDMA_DROP:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_DROP;
            case CDMA_INTERCEPT:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_INTERCEPT;
            case CDMA_REORDER:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_REORDER;
            case CDMA_SO_REJECT:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_SO_REJECT;
            case CDMA_RETRY_ORDER:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_RETRY_ORDER;
            case CDMA_ACCESS_FAILURE:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_ACCESS_FAILURE;
            case CDMA_PREEMPTED:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_PREEMPTED;
            case CDMA_NOT_EMERGENCY:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_NOT_EMERGENCY;
            case CDMA_ACCESS_BLOCKED:
                return TelephonyManager.DISCONNECT_CAUSE_CDMA_ACCESS_BLOCKED;
            default:
                return TelephonyManager.DISCONNECT_CAUSE_ERROR_UNSPECIFIED;
        }
    }

    /**
     * Convert the TelephonyManager.DISCONNECT_CAUSE_* constants into the {@link DisconnectCause} enum
     * for the public API.
     */
    public static Connection.DisconnectCause convertDisconnectCause(int disconnectCause) {
        switch (disconnectCause) {
            case TelephonyManager.DISCONNECT_CAUSE_NOT_DISCONNECTED:
                return Connection.DisconnectCause.NOT_DISCONNECTED;
            case TelephonyManager.DISCONNECT_CAUSE_INCOMING_MISSED:
                return Connection.DisconnectCause.INCOMING_MISSED;
            case TelephonyManager.DISCONNECT_CAUSE_NORMAL:
                return Connection.DisconnectCause.NORMAL;
            case TelephonyManager.DISCONNECT_CAUSE_LOCAL:
                return Connection.DisconnectCause.LOCAL;
            case TelephonyManager.DISCONNECT_CAUSE_BUSY:
                return Connection.DisconnectCause.BUSY;
            case TelephonyManager.DISCONNECT_CAUSE_CONGESTION:
                return Connection.DisconnectCause.CONGESTION;
            case TelephonyManager.DISCONNECT_CAUSE_MMI:
                return Connection.DisconnectCause.MMI;
            case TelephonyManager.DISCONNECT_CAUSE_INVALID_NUMBER:
                return Connection.DisconnectCause.INVALID_NUMBER;
            case TelephonyManager.DISCONNECT_CAUSE_NUMBER_UNREACHABLE:
                return Connection.DisconnectCause.NUMBER_UNREACHABLE;
            case TelephonyManager.DISCONNECT_CAUSE_SERVER_UNREACHABLE:
                return Connection.DisconnectCause.SERVER_UNREACHABLE;
            case TelephonyManager.DISCONNECT_CAUSE_INVALID_CREDENTIALS:
                return Connection.DisconnectCause.INVALID_CREDENTIALS;
            case TelephonyManager.DISCONNECT_CAUSE_OUT_OF_NETWORK:
                return Connection.DisconnectCause.OUT_OF_NETWORK;
            case TelephonyManager.DISCONNECT_CAUSE_SERVER_ERROR:
                return Connection.DisconnectCause.SERVER_ERROR;
            case TelephonyManager.DISCONNECT_CAUSE_TIMED_OUT:
                return Connection.DisconnectCause.TIMED_OUT;
            case TelephonyManager.DISCONNECT_CAUSE_LOST_SIGNAL:
                return Connection.DisconnectCause.LOST_SIGNAL;
            case TelephonyManager.DISCONNECT_CAUSE_LIMIT_EXCEEDED:
                return Connection.DisconnectCause.LIMIT_EXCEEDED;
            case TelephonyManager.DISCONNECT_CAUSE_INCOMING_REJECTED:
                return Connection.DisconnectCause.INCOMING_REJECTED;
            case TelephonyManager.DISCONNECT_CAUSE_POWER_OFF:
                return Connection.DisconnectCause.POWER_OFF;
            case TelephonyManager.DISCONNECT_CAUSE_OUT_OF_SERVICE:
                return Connection.DisconnectCause.OUT_OF_SERVICE;
            case TelephonyManager.DISCONNECT_CAUSE_ICC_ERROR:
                return Connection.DisconnectCause.ICC_ERROR;
            case TelephonyManager.DISCONNECT_CAUSE_CALL_BARRED:
                return Connection.DisconnectCause.CALL_BARRED;
            case TelephonyManager.DISCONNECT_CAUSE_FDN_BLOCKED:
                return Connection.DisconnectCause.FDN_BLOCKED;
            case TelephonyManager.DISCONNECT_CAUSE_CS_RESTRICTED:
                return Connection.DisconnectCause.CS_RESTRICTED;
            case TelephonyManager.DISCONNECT_CAUSE_CS_RESTRICTED_NORMAL:
                return Connection.DisconnectCause.CS_RESTRICTED_NORMAL;
            case TelephonyManager.DISCONNECT_CAUSE_CS_RESTRICTED_EMERGENCY:
                return Connection.DisconnectCause.CS_RESTRICTED_EMERGENCY;
            case TelephonyManager.DISCONNECT_CAUSE_UNOBTAINABLE_NUMBER:
                return Connection.DisconnectCause.UNOBTAINABLE_NUMBER;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_LOCKED_UNTIL_POWER_CYCLE:
                return Connection.DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_DROP:
                return Connection.DisconnectCause.CDMA_DROP;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_INTERCEPT:
                return Connection.DisconnectCause.CDMA_INTERCEPT;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_REORDER:
                return Connection.DisconnectCause.CDMA_REORDER;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_SO_REJECT:
                return Connection.DisconnectCause.CDMA_SO_REJECT;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_RETRY_ORDER:
                return Connection.DisconnectCause.CDMA_RETRY_ORDER;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_ACCESS_FAILURE:
                return Connection.DisconnectCause.CDMA_ACCESS_FAILURE;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_PREEMPTED:
                return Connection.DisconnectCause.CDMA_PREEMPTED;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_NOT_EMERGENCY:
                return Connection.DisconnectCause.CDMA_NOT_EMERGENCY;
            case TelephonyManager.DISCONNECT_CAUSE_CDMA_ACCESS_BLOCKED:
                return Connection.DisconnectCause.CDMA_ACCESS_BLOCKED;
            default:
                return Connection.DisconnectCause.ERROR_UNSPECIFIED;
        }
    }
}
