package com.craxiom.networksurvey;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.craxiom.networksurvey.helpers.TestUtils;
import com.craxiom.networksurvey.models.tableschemas.MessageTableSchema;

import org.junit.Before;
import org.junit.Rule;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.GeoPackageManager;

import static com.google.common.truth.Truth.assertWithMessage;

public class TestBase
{
    private static final String LOG_TAG = TestBase.class.getSimpleName();
    public static LocalDate testRunStartTime = LocalDate.now();
    public static String testRunDate;
    public static GeoPackage geoPackage;
    public static GeoPackageManager geoPackageManager;

    @Rule
    public ActivityScenarioRule<NetworkSurveyActivity> activityScenarioRule = new ActivityScenarioRule<>(NetworkSurveyActivity.class);

    @Rule
    public GrantPermissionRule mGrantPermissionRule =
            GrantPermissionRule.grant(
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_PHONE_STATE",
                    "android.permission.ACCESS_BACKGROUND_LOCATION");

    @Before
    public void setUp()
    {
        if (TestUtils.System.areSystemAnimationsEnabled())
        {
            Log.w(LOG_TAG, "For best test stability, please disable animations. " +
                    "https://developer.android.com/training/testing/espresso/setup#set-up-environment");
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        testRunDate = formatter.format(testRunStartTime);
    }

    public Context getContext()
    {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * Validates the columns in the table schema that are common to all messages types.
     *
     * @param results The results that define the table schema to validate.
     * @since 1.5.0
     */
    public void validateCommonTableSchema(ArrayList<MessageTableSchema> results)
    {
        assertWithMessage("Results are not empty.")
                .that(results)
                .isNotEmpty();

        assertWithMessage("Validate ID column schema")
                .that(results.get(0).toString())
                .isEqualTo("MessageTableSchemaModel{cid=0, name='id', type='3', notNull=1, defaultValue=0, primaryKey=1}");

        assertWithMessage("Validate GEOM column schema")
                .that(results.get(1).toString())
                .isEqualTo("MessageTableSchemaModel{cid=1, name='geom', type='3', notNull=0, defaultValue=0, primaryKey=0}");

        assertWithMessage("Validate Time column schema")
                .that(results.get(2).toString())
                .isEqualTo("MessageTableSchemaModel{cid=2, name='Time', type='3', notNull=0, defaultValue=0, primaryKey=0}");

        assertWithMessage("Validate Mission ID column name")
                .that(results.get(3).getName())
                .isEqualTo("MissionId");
        assertWithMessage("Validate Mission ID column type")
                .that(results.get(3).getType())
                .isEqualTo("3");

        assertWithMessage("Validate Record Number column schema")
                .that(results.get(4).toString())
                .isEqualTo("MessageTableSchemaModel{cid=4, name='RecordNumber', type='3', notNull=1, defaultValue=-1, primaryKey=0}");
    }
}
