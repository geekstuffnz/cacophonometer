package nz.org.cacophony.cacophonometer;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

import static android.content.Context.POWER_SERVICE;
import static nz.org.cacophony.cacophonometer.Util.getBatteryLevelByIntent;

/**
 * This class receives the intents that indicate that a recording is required to be made.  Before a
 * recording is initiated a number of 'house keeping' tasks/checks are made including creating the
 * next alarms and checking the phone has enough power to proceed.
 *
 * Depending on how a recording request is made (ie from Android alarm or the 'Record Now' button)
 * either a service or thread is used to proceed to making the actual recording - this was imposed
 * by the Android OS.
 */

public class StartRecordingReceiver extends BroadcastReceiver{

    private static final String TAG = StartRecordingReceiver.class.getName();


    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void onReceive(final Context context, Intent intent) {
        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (powerManager == null){
            Log.e(TAG, "PowerManger is null");
            return;
        }

        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "StartRecordingReceiverWakelockTag");
        wakeLock.acquire(10*60*1000L /*10 minutes*/);
        try {
            Util.createAlarms(context);
            DawnDuskAlarms.configureDawnAndDuskAlarms(context, false);

            if (!Util.checkPermissionsForRecording(context)) {
                Log.e(TAG, "Don't have proper permissions to record");

                // Need to enable record button
                Util.broadcastAMessage(context, "no_permission_to_record");
                return;
            }
            Prefs prefs = new Prefs(context);

            // need to determine the source of the intent ie Main UI or boot receiver
            Bundle bundle = intent.getExtras();
            if (bundle == null){
                Log.e(TAG, "bundle is null");
                return;
            }
            final String alarmIntentType = bundle.getString("type");

            if (alarmIntentType == null) {
                Log.e(TAG, "Intent does not have a type");
                return;
            }

            // First check to see if battery level is sufficient to continue.

            double batteryLevel = Util.getBatteryLevelUsingSystemFile();
            if (batteryLevel != -1) { // looks like getting battery level using system file worked
                String batteryStatus = Util.getBatteryStatus(context);
                prefs.setBatteryLevel(batteryLevel); // had to put it into prefs as I could not ready battery level from UploadFiles class (looper error)
                if (batteryStatus.equalsIgnoreCase("FULL")) {
                    // The current battery level must be the maximum it can be!
                    prefs.setMaximumBatteryLevel(batteryLevel);
                }

                double batteryRatioLevel = batteryLevel / prefs.getMaximumBatteryLevel();
                double batteryPercent = batteryRatioLevel * 100;
                if (!enoughBatteryToContinue(batteryPercent, alarmIntentType, prefs)) {
                    Log.w(TAG, "Battery level too low to do a recording");
                    return;
                }

            } else { // will need to get battery level using intent method
                double batteryPercentLevel = getBatteryLevelByIntent(context);

                if (!enoughBatteryToContinue(batteryPercentLevel, alarmIntentType, prefs)) {
                    Log.w(TAG, "Battery level too low to do a recording");
                    return;
                }
            }

            String mode = prefs.getMode();
            switch (mode) {
                case "off":
                    if (prefs.getPeriodicallyUpdateGPS()) {
// Don't do anything
                    }

                    break;
                case "normal":
                    // Don't update location
                    break;
                case "normalOnline":
                    // Don't update location
                    break;

                case "walking":
                    // Not going to do dawn/dusk alarms if in walking mode
                    if (alarmIntentType.equalsIgnoreCase("dawn") || alarmIntentType.equalsIgnoreCase("dusk")) {
                        return; // exit onReceive method
                    }

                    break;
            }

            // need to determine the source of the intent ie Main UI or boot receiver

             if (alarmIntentType.equalsIgnoreCase("recordNowButton")) {
                try {
                    // Start recording in new thread.

                    Thread thread = new Thread() {
                        @Override
                        public void run() {
                            MainThread mainThread = new MainThread(context, alarmIntentType);
                            mainThread.run();
                        }
                    };
                    thread.start();
                } catch (Exception e) {
                    e.printStackTrace();
                }


            } else { // intent came from boot receiver or app (not test record, or walk )

                Intent mainServiceIntent = new Intent(context, MainService.class);
                try {
                    mainServiceIntent.putExtra("type", alarmIntentType);

                } catch (Exception e) {
                    Log.e(TAG, "Error setting up intent");

                }
                context.startService(mainServiceIntent);


            }

        }catch (Exception ex){
            Log.e(TAG, ex.getLocalizedMessage());

        }finally{
            wakeLock.release();
        }

    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean enoughBatteryToContinue(double batteryPercent, String alarmType, Prefs prefs){
        // The battery level required to continue depends on the type of alarm

        if (alarmType.equalsIgnoreCase("recordNowButton") ){
            // record now button was pressed
            return true;
        }


        String mode = prefs.getMode();
        switch(mode) { // mode determined earlier
            case "off":
                // has no affect on decision
                break;
            case "normal":
                // has no affect on decision
                break;
            case "normalOnline":
                // has no affect on decision
                break;

            case "walking":
                return true;  // ignore battery level when in walking mode

        }

        if (alarmType.equalsIgnoreCase("repeating")){

            return batteryPercent > prefs.getBatteryLevelCutoffRepeatingRecordings();
        }else { // must be a dawn or dusk alarm

            return batteryPercent > prefs.getBatteryLevelCutoffDawnDuskRecordings();
        }

    }






}