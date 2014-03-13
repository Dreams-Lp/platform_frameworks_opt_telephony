/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
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

import android.app.PendingIntent;
import android.os.ServiceManager;
import android.telephony.Rlog;

import com.android.internal.telephony.ISms;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SmsRawData;

import java.util.ArrayList;
import java.util.List;

/**
 * UiccSmsController to provide an inter-process communication to
 * access Sms in Icc.
 */
public class UiccSmsController extends ISms.Stub {
    static final String LOG_TAG = "RIL_UiccSmsController";

    protected Phone[] mPhone;

    protected UiccSmsController(Phone[] phone){
        mPhone = phone;

        if (ServiceManager.getService("isms") == null) {
            ServiceManager.addService("isms", this);
        }
    }

    public boolean
    updateMessageOnIccEf(String callingPackage, int index, int status, byte[] pdu) throws android.os.RemoteException {
        return  updateMessageOnIccEfUsingSub(getPreferredSmsSubscription(), callingPackage, index, status, pdu);
    }
    public boolean
    updateMessageOnIccEfUsingSub(long subId, String callingPackage, int index, int status, byte[] pdu)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.updateMessageOnIccEf(callingPackage, index, status, pdu);
        } else {
            Rlog.e(LOG_TAG,"updateMessageOnIccEf iccSmsIntMgr is null" +
                          " for Subscription: " + subId);
            return false;
        }
    }

    public boolean copyMessageToIccEf(String callingPackage, int status, byte[] pdu, byte[] smsc) throws android.os.RemoteException {
        return copyMessageToIccEfUsingSub(getPreferredSmsSubscription(), callingPackage, status, pdu,
            smsc);
    }

    public boolean copyMessageToIccEfUsingSub(long subId, String callingPackage, int status, byte[] pdu,
            byte[] smsc) throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.copyMessageToIccEf(callingPackage, status, pdu, smsc);
        } else {
            Rlog.e(LOG_TAG,"copyMessageToIccEf iccSmsIntMgr is null" +
                          " for Subscription: " + subId);
            return false;
        }
    }

    public List<SmsRawData> getAllMessagesFromIccEf(String callingPackage) throws android.os.RemoteException {
        return getAllMessagesFromIccEfUsingSub(getPreferredSmsSubscription(), callingPackage);
    }
    public List<SmsRawData> getAllMessagesFromIccEfUsingSub(long subId, String callingPackage)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.getAllMessagesFromIccEf(callingPackage);
        } else {
            Rlog.e(LOG_TAG,"getAllMessagesFromIccEf iccSmsIntMgr is" +
                          " null for Subscription: " + subId);
            return null;
        }
    }

    public void sendData(String callingPackage, String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
         sendDataUsingSub(getPreferredSmsSubscription(), callingPackage, destAddr, scAddr, destPort,
            data, sentIntent, deliveryIntent);
    }

    public void sendDataUsingSub(long subId, String callingPackage, String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendData(callingPackage, destAddr, scAddr, destPort, data,
                    sentIntent, deliveryIntent);
        } else {
            Rlog.e(LOG_TAG,"sendText iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }
    public void sendText(String callingPackage, String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        sendTextUsingSub(getPreferredSmsSubscription(), callingPackage, destAddr, scAddr,
            text, sentIntent, deliveryIntent);
    }

    public void sendTextUsingSub(long subId, String callingPackage, String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendText(callingPackage, destAddr, scAddr, text, sentIntent,
                    deliveryIntent);
        } else {
            Rlog.e(LOG_TAG,"sendText iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }

    public void sendMultipartText(String callingPackage, String destAddr, String scAddr,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) throws android.os.RemoteException {
         sendMultipartTextUsingSub(getPreferredSmsSubscription(), callingPackage, destAddr, scAddr,
            parts, sentIntents, deliveryIntents);
    }

    public void sendMultipartTextUsingSub(long subId, String callingPackage, String destAddr, String scAddr,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents)
            throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            iccSmsIntMgr.sendMultipartText(callingPackage, destAddr, scAddr, parts, sentIntents,
                    deliveryIntents);
        } else {
            Rlog.e(LOG_TAG,"sendMultipartText iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
    }
    public boolean enableCellBroadcast(int messageIdentifier) throws android.os.RemoteException {
        return enableCellBroadcastUsingSub(getPreferredSmsSubscription(), messageIdentifier);
    }

    public boolean enableCellBroadcastUsingSub(long subId, int messageIdentifier)
                throws android.os.RemoteException {
        return enableCellBroadcastRangeUsingSub(subId, messageIdentifier, messageIdentifier);
    }

    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId) throws android.os.RemoteException {
        return enableCellBroadcastRangeUsingSub(getPreferredSmsSubscription(), startMessageId, endMessageId);
    }

    public boolean enableCellBroadcastRangeUsingSub(long subId, int startMessageId, int endMessageId)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.enableCellBroadcastRange(startMessageId, endMessageId);
        } else {
            Rlog.e(LOG_TAG,"enableCellBroadcast iccSmsIntMgr is null for" +
                          " Subscription: " + subId);
        }
        return false;
    }

    public boolean disableCellBroadcast(int messageIdentifier) throws android.os.RemoteException {
        return disableCellBroadcastUsingSub(getPreferredSmsSubscription(), messageIdentifier);
    }

    public boolean disableCellBroadcastUsingSub(long subId, int messageIdentifier)
                throws android.os.RemoteException {
        return disableCellBroadcastRangeUsingSub(subId, messageIdentifier, messageIdentifier);
    }

    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId) throws android.os.RemoteException {
        return disableCellBroadcastRangeUsingSub(getPreferredSmsSubscription(), startMessageId, endMessageId);
    }

    public boolean disableCellBroadcastRangeUsingSub(long subId, int startMessageId, int endMessageId)
                throws android.os.RemoteException {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.disableCellBroadcastRange(startMessageId, endMessageId);
        } else {
            Rlog.e(LOG_TAG,"disableCellBroadcast iccSmsIntMgr is null for" +
                          " Subscription:"+subId);
        }
       return false;
    }

    public int getPremiumSmsPermission(String packageName) {
        return getPremiumSmsPermissionUsingSub(getPreferredSmsSubscription(), packageName);
    }

    @Override
    public int getPremiumSmsPermissionUsingSub(long subId, String packageName) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.getPremiumSmsPermission(packageName);
        } else {
            Rlog.e(LOG_TAG, "getPremiumSmsPermission iccSmsIntMgr is null");
        }
        //TODO Rakesh
        return 0;
    }

    public void setPremiumSmsPermission(String packageName, int permission) {
         setPremiumSmsPermissionUsingSub(getPreferredSmsSubscription(), packageName, permission);
    }

    @Override
    public void setPremiumSmsPermissionUsingSub(long subId, String packageName, int permission) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            iccSmsIntMgr.setPremiumSmsPermission(packageName, permission);
        } else {
            Rlog.e(LOG_TAG, "setPremiumSmsPermission iccSmsIntMgr is null");
        }
    }

    public boolean isImsSmsSupported() {
        return isImsSmsSupportedUsingSub(getPreferredSmsSubscription());
    }

    @Override
    public boolean isImsSmsSupportedUsingSub(long subId) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.isImsSmsSupported();
        } else {
            Rlog.e(LOG_TAG, "isImsSmsSupported iccSmsIntMgr is null");
        }
        return false;
    }

    public String getImsSmsFormat() {
        return getImsSmsFormatUsingSub(getPreferredSmsSubscription());
    }

    @Override
    public String getImsSmsFormatUsingSub(long subId) {
       IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null ) {
            return iccSmsIntMgr.getImsSmsFormat();
        } else {
            Rlog.e(LOG_TAG, "getImsSmsFormat iccSmsIntMgr is null");
        }
        return null;
    }

    /**
     * get sms interface manager object based on subscription.
     **/
    private IccSmsInterfaceManager getIccSmsInterfaceManager(long subId) {
        long phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        try {
            return (IccSmsInterfaceManager)
                ((PhoneProxy)mPhone[(int)phoneId]).getIccSmsInterfaceManager();
        } catch (NullPointerException e) {
            Rlog.e(LOG_TAG, "Exception is :"+e.toString()+" For subscription :"+subId );
            e.printStackTrace(); //This will print stact trace
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Rlog.e(LOG_TAG, "Exception is :"+e.toString()+" For subscription :"+subId );
            e.printStackTrace(); //This will print stack trace
            return null;
        }
    }

    /**
       Gets User preferred SMS subscription */
    public long getPreferredSmsSubscription() {
        return  PhoneFactory.getDefaultSubscription();
    }

    /**
     * Get SMS prompt property,  enabled or not
     **/
    public boolean isSMSPromptEnabled() {
        return PhoneFactory.isSMSPromptEnabled();
    }

}
