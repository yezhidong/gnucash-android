/*
 * Copyright (c) 2015 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gnucash.android.service;

import android.app.IntentService;
import android.content.ContentValues;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.PowerManager;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.crashlytics.android.Crashlytics;

import org.gnucash.android.app.GnuCashApplication;
import org.gnucash.android.db.DatabaseHelper;
import org.gnucash.android.db.DatabaseSchema;
import org.gnucash.android.db.adapter.BooksDbAdapter;
import org.gnucash.android.db.adapter.DatabaseAdapter;
import org.gnucash.android.db.adapter.RecurrenceDbAdapter;
import org.gnucash.android.db.adapter.ScheduledActionDbAdapter;
import org.gnucash.android.db.adapter.SplitsDbAdapter;
import org.gnucash.android.db.adapter.TransactionsDbAdapter;
import org.gnucash.android.export.ExportAsyncTask;
import org.gnucash.android.export.ExportParams;
import org.gnucash.android.model.Book;
import org.gnucash.android.model.ScheduledAction;
import org.gnucash.android.model.Transaction;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Service for running scheduled events.
 * <p>The service is started and goes through all scheduled event entries in the the database and executes them.
 * Then it is stopped until the next time it is run. <br>
 * Scheduled runs of the service should be achieved using an {@link android.app.AlarmManager}</p>
 * @author Ngewi Fet <ngewif@gmail.com>
 */
public class ScheduledActionService extends IntentService {

    public static final String LOG_TAG = "ScheduledActionService";

    public ScheduledActionService() {
        super(LOG_TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.i(LOG_TAG, "Starting scheduled action service");

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        wakeLock.acquire();

        try {
            BooksDbAdapter booksDbAdapter = BooksDbAdapter.getInstance();
            List<Book> books = booksDbAdapter.getAllRecords();
            for (Book book : books) {
                DatabaseHelper dbHelper = new DatabaseHelper(GnuCashApplication.getAppContext(), book.getUID());
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                RecurrenceDbAdapter recurrenceDbAdapter = new RecurrenceDbAdapter(db);
                ScheduledActionDbAdapter scheduledActionDbAdapter = new ScheduledActionDbAdapter(db, recurrenceDbAdapter);

                List<ScheduledAction> scheduledActions = scheduledActionDbAdapter.getAllEnabledScheduledActions();
                Log.i(LOG_TAG, String.format("Processing %d total scheduled actions for Book: %s",
                        scheduledActions.size(), book.getDisplayName()));
                processScheduledActions(scheduledActions, db);
            }

            Log.i(LOG_TAG, "Completed service @ " + java.text.DateFormat.getDateTimeInstance().format(new Date()));

        } finally { //release the lock either way
            wakeLock.release();
        }

    }

    /**
     * Process scheduled actions and execute any pending actions
     * @param scheduledActions List of scheduled actions
     */
    //made public static for testing. Do not call these methods directly
    @VisibleForTesting
    public static void processScheduledActions(List<ScheduledAction> scheduledActions, SQLiteDatabase db) {
        for (ScheduledAction scheduledAction : scheduledActions) {

            long now        = System.currentTimeMillis();
            int totalPlannedExecutions = scheduledAction.getTotalPlannedExecutionCount();
            int executionCount = scheduledAction.getExecutionCount();

            if (scheduledAction.getStartTime() > now    //if schedule begins in the future
                    || !scheduledAction.isEnabled()     // of if schedule is disabled
                    || (totalPlannedExecutions > 0 && executionCount >= totalPlannedExecutions)) { //limit was set and we reached or exceeded it
                Log.i(LOG_TAG, "Skipping scheduled action: " + scheduledAction.toString());
                continue;
            }
            executeScheduledEvent(scheduledAction, db);
        }
    }

    /**
     * Executes a scheduled event according to the specified parameters
     * @param scheduledAction ScheduledEvent to be executed
     */
    //made public static for testing. Do not call directly
    @VisibleForTesting
    public static void executeScheduledEvent(ScheduledAction scheduledAction, SQLiteDatabase db){
        Log.i(LOG_TAG, "Executing scheduled action: " + scheduledAction.toString());
        int executionCount = scheduledAction.getExecutionCount();

        switch (scheduledAction.getActionType()){
            case TRANSACTION:
                String actionUID = scheduledAction.getActionUID();
                TransactionsDbAdapter transactionsDbAdapter = new TransactionsDbAdapter(db, new SplitsDbAdapter(db));
                Transaction trxnTemplate = transactionsDbAdapter.getRecord(actionUID);

                long now = System.currentTimeMillis();
                //if there is an end time in the past, we execute all schedules up to the end time.
                //if the end time is in the future, we execute all schedules until now (current time)
                //if there is no end time, we execute all schedules until now
                long endTime = scheduledAction.getEndTime() > 0 ? Math.min(scheduledAction.getEndTime(), now) : now;
                int totalPlannedExecutions = scheduledAction.getTotalPlannedExecutionCount();
                List<Transaction> transactions = new ArrayList<>();

                //we may be executing scheduled action significantly after scheduled time (depending on when Android fires the alarm)
                //so compute the actual transaction time from pre-known values
                long transactionTime = scheduledAction.computeNextScheduledExecutionTime();
                while (transactionTime <= endTime) {
                    Transaction recurringTrxn = new Transaction(trxnTemplate, true);
                    recurringTrxn.setTime(transactionTime);
                    transactions.add(recurringTrxn);
                    recurringTrxn.setScheduledActionUID(scheduledAction.getUID());
                    scheduledAction.setExecutionCount(++executionCount);

                    if (totalPlannedExecutions > 0 && executionCount >= totalPlannedExecutions)
                        break; //if we hit the total planned executions set, then abort
                    transactionTime = scheduledAction.computeNextScheduledExecutionTime();
                }

                transactionsDbAdapter.bulkAddRecords(transactions, DatabaseAdapter.UpdateMethod.insert);
                break;

            case BACKUP:
                ExportParams params = ExportParams.parseCsv(scheduledAction.getTag());
                try {
                    //wait for async task to finish before we proceed (we are holding a wake lock)
                    new ExportAsyncTask(GnuCashApplication.getAppContext(), db).execute(params).get();
                    scheduledAction.setExecutionCount(++executionCount);
                } catch (InterruptedException | ExecutionException e) {
                    Crashlytics.logException(e);
                    Log.e(LOG_TAG, e.getMessage());
                    return; //return immediately, do not update last run time of event
                }
                break;
        }

        //the last run time is computed instead of just using "now" so that if the more than
        // one period has been skipped, all intermediate transactions can be created

        //update the last run time and execution count
        ContentValues contentValues = new ContentValues();
        contentValues.put(DatabaseSchema.ScheduledActionEntry.COLUMN_LAST_RUN, System.currentTimeMillis());
        contentValues.put(DatabaseSchema.ScheduledActionEntry.COLUMN_EXECUTION_COUNT, executionCount);
        new ScheduledActionDbAdapter(db, new RecurrenceDbAdapter(db)).updateRecord(scheduledAction.getUID(), contentValues);

        //set the values in the object because they will be checked for the next iteration in the calling loop
        scheduledAction.setExecutionCount(executionCount);
    }
}
