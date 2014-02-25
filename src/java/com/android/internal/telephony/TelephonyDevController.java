/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.internal.telephony.HardwareConfig;
import com.android.internal.telephony.RIL;
import android.telephony.TelephonyManager;

import android.os.AsyncResult;
import android.telephony.Rlog;
import java.util.BitSet;
import java.util.List;
import java.util.ArrayList;
import android.text.TextUtils;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.ServiceState;

/**
 * TelephonyDevController - provides a unified view of the
 * telephony hardware resources on a device.
 *
 * manages the set of HardwareConfig for the framework.
 */
public class TelephonyDevController extends Handler {
   private static final String LOG_TAG = "TelephonyDevController";
   private static final boolean DBG = true;
   private static final Object mLock = new Object();

   private static final int EVENT_HARDWARE_CONFIG_CHANGED = 1;

   private static TelephonyDevController sTelephonyDevController;
   private static ArrayList<HardwareConfig> mModems = new ArrayList<HardwareConfig>();
   private static ArrayList<HardwareConfig> mSims = new ArrayList<HardwareConfig>();

   private static Message sRilHardwareConfig;

   public static TelephonyDevController create() {
      synchronized (mLock) {
         if (sTelephonyDevController != null) {
            throw new RuntimeException("TelephonyDevController already created!?!");
         }
         sTelephonyDevController = new TelephonyDevController();
         return sTelephonyDevController;
      }
   }

   public static TelephonyDevController getInstance() {
      synchronized (mLock) {
         if (sTelephonyDevController == null) {
            throw new RuntimeException("TelephonyDevController not yet created!?!");
         }
         return sTelephonyDevController;
      }
   }

   private TelephonyDevController() {
      if (DBG) Rlog.d(LOG_TAG, "contructor");

      /* create initial database and content if needed, or populate
       * from existig one.
       */

      mModems.trimToSize();
      mSims.trimToSize();
   }

   /**
    * each RIL call this interface to register/unregister the unsolicited hardware
    * configuration callback data it can provide.
    */
   public static void registerRIL(CommandsInterface cmdsIf) {
      synchronized (mLock) {
         /* get the current configuration from this ril... */
         cmdsIf.getHardwareConfig(sRilHardwareConfig);
         /* ... process it ... */
         AsyncResult ar = (AsyncResult) sRilHardwareConfig.obj;
         handleGetHardwareConfigChanged(ar);
         /* and register for async device configuration change. */
         cmdsIf.registerForHardwareConfigChanged(sTelephonyDevController, EVENT_HARDWARE_CONFIG_CHANGED, null);
      }
   }

   public static void unregisterRIL(CommandsInterface cmdsIf) {
      synchronized (mLock) {
         cmdsIf.unregisterForHardwareConfigChanged(sTelephonyDevController);
      }
   }

   /**
    * handle callbacks from RIL.
    */
   public void handleMessage(Message msg) {
      AsyncResult ar;
      switch (msg.what) {
         case EVENT_HARDWARE_CONFIG_CHANGED:
            if (DBG) Rlog.d(LOG_TAG, "received EVENT_HARDWARE_CONFIG_CHANGED");
               ar = (AsyncResult) msg.obj;
               handleGetHardwareConfigChanged(ar);
            break;
         default:
            Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
      }
   }

   /**
    * hardware configuration update or insert.
    */
   private static void updateOrInsert(HardwareConfig hw, ArrayList<HardwareConfig> list) {
      int size;
      HardwareConfig item;
      synchronized (mLock) {
         size = list.size();
         for (int i = 0 ; i < size ; i++) {
            item = list.get(i);
            if (item.uuid.compareTo(hw.uuid) == 0) {
               list.remove(i);
            }
            list.add(hw);
         }
      }
   }

   /**
    * hardware configuration changed.
    */
   private static void handleGetHardwareConfigChanged(AsyncResult ar) {
      if ((ar.exception == null) && (ar.result != null)) {
         List hwcfg = (List)ar.result;
         for (int i = 0 ; i < hwcfg.size() ; i++) {
            HardwareConfig hw = null;

            hw = (HardwareConfig) hwcfg.get(i);
            if (hw != null) {
               if (hw.type == HardwareConfig.DEV_HARDWARE_TYPE_MODEM) {
                  updateOrInsert(hw, mModems);
               } else if (hw.type == HardwareConfig.DEV_HARDWARE_TYPE_SIM) {
                  updateOrInsert(hw, mSims);
               }
            }
         }
      } else {
         /* erorr detected, ignore.  are we missing some real time configutation
          * at this point?  what to do...
          */
         Rlog.e(LOG_TAG, "handleGetHardwareConfigChanged - returned an error.");
      }
   }

   /**
    * get total number of registered modem.
    */
   public static int getModemCount() {
      synchronized (mLock) {
         return mModems.size();
      }
   }

   /**
    * get modem at index 'index'.
    */
   public HardwareConfig getModem(int index) {
      synchronized (mLock) {
         if (mModems.isEmpty()) {
            Rlog.e(LOG_TAG, "no registered modem device?!?");
            return null;
         }

         if (index > getModemCount()) {
            Rlog.e(LOG_TAG, "out-of-bounds access for modem device " + index + " max: " + getModemCount());
            return null;
         }

         if (DBG) Rlog.d(LOG_TAG, "getModem " + index);
         return mModems.get(index);
      }
   }

   /**
    * get total number of registered sims.
    */
   public int getSimCount() {
      synchronized (mLock) {
         return mSims.size();
      }
   }

   /**
    * get sim at index 'index'.
    */
   public HardwareConfig getSim(int index) {
      synchronized (mLock) {
         if (mSims.isEmpty()) {
            Rlog.e(LOG_TAG, "no registered sim device?!?");
            return null;
         }

         if (index > getSimCount()) {
            Rlog.e(LOG_TAG, "out-of-bounds access for sim device " + index + " max: " + getSimCount());
            return null;
         }

         if (DBG) Rlog.d(LOG_TAG, "getSim " + index);
            return mSims.get(index);
      }
   }

   /**
    * get modem associated with sim index 'simIndex'.
    */
   public HardwareConfig getModemForSim(int simIndex) {
      synchronized (mLock) {
         if (mModems.isEmpty() || mSims.isEmpty()) {
            Rlog.e(LOG_TAG, "no registered modem/sim device?!?");
            return null;
         }

         if (simIndex > getSimCount()) {
            Rlog.e(LOG_TAG, "out-of-bounds access for sim device " + simIndex + " max: " + getSimCount());
            return null;
         }

         if (DBG) Rlog.d(LOG_TAG, "getModemForSim " + simIndex);

         HardwareConfig sim = getSim(simIndex);
         for (HardwareConfig modem: mModems) {
            if (modem.uuid.equals(sim.modemUuid)) {
               return modem;
            }
         }

         return null;
      }
   }

   /**
    * get all sim's associated with modem at index 'modemIndex'.
    */
   public ArrayList<HardwareConfig> getAllSimsForModem(int modemIndex) {
      synchronized (mLock) {
         if (mSims.isEmpty()) {
            Rlog.e(LOG_TAG, "no registered sim device?!?");
            return null;
         }

         if (modemIndex > getModemCount()) {
            Rlog.e(LOG_TAG, "out-of-bounds access for modem device " + modemIndex + " max: " + getModemCount());
            return null;
         }

         if (DBG) Rlog.d(LOG_TAG, "getAllSimsForModem " + modemIndex);

         ArrayList<HardwareConfig> result = new ArrayList<HardwareConfig>();
         HardwareConfig modem = getModem(modemIndex);
         for (HardwareConfig sim: mSims) {
            if (sim.modemUuid.equals(modem.uuid)) {
               result.add(sim);
            }
         }
         return result;
      }
   }

   /**
    * get all modem's registered.
    */
   public ArrayList<HardwareConfig> getAllModems() {
      synchronized (mLock) {
         ArrayList<HardwareConfig> modems = new ArrayList<HardwareConfig>();
         if (mModems.isEmpty()) {
            Rlog.d(LOG_TAG, "getAllModems - empty list.");
         } else {
            for (HardwareConfig modem: mModems) {
               modems.add(modem);
            }
         }

         return modems;
      }
   }

   /**
    * get all sim's registered.
    */
   public ArrayList<HardwareConfig> getAllSims() {
      synchronized (mLock) {
         ArrayList<HardwareConfig> sims = new ArrayList<HardwareConfig>();
         if (mSims.isEmpty()) {
            Rlog.d(LOG_TAG, "getAllSims - empty list.");
         } else {
            for (HardwareConfig sim: mSims) {
               sims.add(sim);
            }
         }

         return sims;
      }
   }
}
