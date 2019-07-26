package com.craxiom.networksurvey;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Environment;
import android.os.Handler;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import com.craxiom.networksurvey.messaging.LteRecord;
import com.google.protobuf.Int32Value;
import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.GeoPackageManager;
import mil.nga.geopackage.core.contents.Contents;
import mil.nga.geopackage.core.contents.ContentsDao;
import mil.nga.geopackage.core.contents.ContentsDataType;
import mil.nga.geopackage.core.srs.SpatialReferenceSystem;
import mil.nga.geopackage.db.GeoPackageDataType;
import mil.nga.geopackage.factory.GeoPackageFactory;
import mil.nga.geopackage.features.columns.GeometryColumns;
import mil.nga.geopackage.features.columns.GeometryColumnsDao;
import mil.nga.geopackage.features.user.FeatureColumn;
import mil.nga.geopackage.features.user.FeatureDao;
import mil.nga.geopackage.features.user.FeatureRow;
import mil.nga.geopackage.features.user.FeatureTable;
import mil.nga.geopackage.geom.GeoPackageGeometryData;
import mil.nga.sf.GeometryType;
import mil.nga.sf.Point;
import mil.nga.sf.proj.ProjectionConstants;

import java.io.File;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static mil.nga.geopackage.db.GeoPackageDataType.MEDIUMINT;

/**
 * Responsible for pulling the network values and populating the UI.  This class can also optionally
 * write the network values to a log file.
 *
 * @since 0.0.2
 */
public class SurveyRecordWriter
{
    private static final String LOG_DIRECTORY_NAME = "NetworkSurveyData";
    private static final SimpleDateFormat formatFilenameFriendlyTime = new SimpleDateFormat("YYYYMMdd-HHmmss", Locale.US);
    private static final String FILE_NAME_PREFIX = "craxiom-lte";
    private static final String LTE_RECORDS_TABLE_NAME = "lte_drive_test_records";
    private static final long WGS84_SRS = 4326;
    private static final String MISSION_ID_PREFIX = "Network Survey ";

    private static final String ID_COLUMN = "id";
    private static final String GEOMETRY_COLUMN = "geom";
    private static final String TIME_COLUMN = "Time";
    private static final String RECORD_NUMBER_COLUMN = "RecordNumber";
    private static final String GROUP_NUMBER_COLUMN = "GroupNumber";
    private static final String MCC_COLUMN = "MCC";
    private static final String MNC_COLUMN = "MNC";
    private static final String TAC_COLUMN = "TAC";
    private static final String CI_COLUMN = "ECI";
    private static final String EARFCN_COLUMN = "DL_EARFCN";
    private static final String PCI_COLUMN = "Phys_Cell_Id";
    private static final String RSRP_COLUMN = "RSRP";
    private static final String RSRQ_COLUMN = "RSRQ";
    private static final String TA_COLUMN = "TA";

    private final String LOG_TAG = SurveyRecordWriter.class.getSimpleName();

    private final LocationManager locationManager;
    private final TelephonyManager telephonyManager;
    private final NetworkSurveyActivity networkSurveyActivity;
    private final String bestProvider;
    private final Handler handler = new Handler();
    private final List<ISurveyRecordListener> surveyRecordListeners = new CopyOnWriteArrayList<>();

    private final String deviceId;
    private final String missionId;

    private volatile boolean loggingEnabled;
    private final GeoPackageManager geoPackageManager;
    private GeoPackage geoPackage;

    private int recordNumber = 0;
    private int groupNumber = -1; // This will be incremented to 0 the first time it is used.

    SurveyRecordWriter(LocationManager locationManager, TelephonyManager telephonyManager,
                       NetworkSurveyActivity networkSurveyActivity, String bestProvider, String deviceId)
    {
        this.locationManager = locationManager;
        this.telephonyManager = telephonyManager;
        this.networkSurveyActivity = networkSurveyActivity;
        this.bestProvider = bestProvider;

        this.deviceId = deviceId;
        missionId = MISSION_ID_PREFIX + formatFilenameFriendlyTime.format(System.currentTimeMillis());

        geoPackageManager = GeoPackageFactory.getManager(networkSurveyActivity);
    }

    public void registerSurveyRecordListener(ISurveyRecordListener surveyRecordListener)
    {
        surveyRecordListeners.add(surveyRecordListener);
    }

    public void unregisterSurveyRecordListener(ISurveyRecordListener surveyRecordListener)
    {
        surveyRecordListeners.remove(surveyRecordListener);
    }

    /**
     * Uses the {@link TelephonyManager} to pull the current network details, displays the values
     * in the UI, and also writes the values out to a log file for other systems to post process.
     */
    void logSurveyRecord() throws SecurityException
    {
        groupNumber++; // Group all the LTE records found in this scan iteration.

        try
        {
            final List<CellInfo> allCellInfo = telephonyManager.getAllCellInfo();
            if (allCellInfo.size() > 0)
            {
                for (CellInfo cellInfo : allCellInfo)
                {
                    // For now, just look for LTE towers
                    if (cellInfo instanceof CellInfoLte)
                    {
                        if (cellInfo.isRegistered())
                        {
                            // This record is for the serving cell
                            if (!parseServingCellInfo((CellInfoLte) cellInfo))
                            {
                                Log.d(LOG_TAG, "The serving LTE record is invalid, skipping all the neighbor records as well.");
                                break;
                            }
                        } else
                        {
                            // This represents a neighbor record
                            parseNeighborCellInfo((CellInfoLte) cellInfo);
                        }
                    }
                }
            }
        } catch (Exception e)
        {
            Log.e(LOG_TAG, "Unable to display and log an LTE Survey Record", e);
        }
    }

    /**
     * Sets up all the GeoPackage stuff so that the LTE records can be written to a log file.
     *
     * @param enable True if logging is being turned on, false if the log file should be closed.
     * @throws SQLException If something goes wrong setting up the GeoPackage log file.
     */
    void enableLogging(boolean enable) throws SQLException
    {
        if (!enable)
        {
            if (loggingEnabled)
            {
                loggingEnabled = false;
                geoPackage.close(); // TODO figure out what else needs to be done to clean up.
                geoPackage = null;
            }

            return;
        }

        if (isExternalStorageWritable())
        {
            //final File loggingFile = getPublicStorageDir(
            //      FILE_NAME_PREFIX + "-" + formatFilenameFriendlyTime.format(System.currentTimeMillis()));
            final String loggingFile = createPublicStorageFilePath();

            //final boolean created = geoPackageManager.createAtPath(DATABASE_NAME, loggingFile);
            //final String loggingFilePath = loggingFile.getAbsolutePath();
            //final boolean created = geoPackageManager.create(loggingFilePath);
            //final boolean created = geoPackageManager.createFile(loggingFile);
            final boolean created = geoPackageManager.create(loggingFile);

            if (!created)
            {
                Log.e(LOG_TAG, "Unable to create the GeoPackage File.  No logging will be recorded.");
                Toast.makeText(networkSurveyActivity.getApplicationContext(), "Error: Unable to create the GeoPackage file.  No logging will be recorded.", Toast.LENGTH_SHORT).show();
                return;
            }

            geoPackage = geoPackageManager.open(loggingFile);
            if (geoPackage == null)
            {
                Log.e(LOG_TAG, "Unable to open the GeoPackage Database.  No logging will be recorded.");
                Toast.makeText(networkSurveyActivity.getApplicationContext(), "Error: Unable to open the GeoPackage file.  No logging will be recorded.", Toast.LENGTH_SHORT).show();
                return;
            }

            final SpatialReferenceSystem spatialReferenceSystem;
            spatialReferenceSystem = geoPackage.getSpatialReferenceSystemDao()
                    .getOrCreateCode(ProjectionConstants.AUTHORITY_EPSG, (long) ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM);

            geoPackage.createGeometryColumnsTable();
            createLteRecordTable(geoPackage, spatialReferenceSystem);
        }

        loggingEnabled = true;
    }

    /**
     * Creates an GeoPackage Table that can be populated with LTE Records.
     *
     * @param geoPackage The GeoPackage to create the table in.
     * @param srs        The SRS to use for the table coordinates.
     * @throws SQLException If there is a problem working with the GeoPackage SQLite DB.
     */
    private void createLteRecordTable(GeoPackage geoPackage, SpatialReferenceSystem srs) throws SQLException
    {
        ContentsDao contentsDao = geoPackage.getContentsDao();

        Contents contents = new Contents();
        contents.setTableName(SurveyRecordWriter.LTE_RECORDS_TABLE_NAME);
        contents.setDataType(ContentsDataType.FEATURES);
        contents.setIdentifier(SurveyRecordWriter.LTE_RECORDS_TABLE_NAME);
        contents.setDescription(SurveyRecordWriter.LTE_RECORDS_TABLE_NAME);
        contents.setSrs(srs);

        int colNum = 0;
        List<FeatureColumn> tblcols = new LinkedList<>();
        tblcols.add(FeatureColumn.createPrimaryKeyColumn(colNum++, ID_COLUMN));
        tblcols.add(FeatureColumn.createGeometryColumn(colNum++, GEOMETRY_COLUMN, GeometryType.POINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, TIME_COLUMN, GeoPackageDataType.INT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, RECORD_NUMBER_COLUMN, MEDIUMINT, true, -1));
        tblcols.add(FeatureColumn.createColumn(colNum++, GROUP_NUMBER_COLUMN, MEDIUMINT, true, -1));

        tblcols.add(FeatureColumn.createColumn(colNum++, MCC_COLUMN, GeoPackageDataType.SMALLINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, MNC_COLUMN, GeoPackageDataType.SMALLINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, TAC_COLUMN, GeoPackageDataType.MEDIUMINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, CI_COLUMN, GeoPackageDataType.MEDIUMINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, EARFCN_COLUMN, GeoPackageDataType.MEDIUMINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, PCI_COLUMN, GeoPackageDataType.SMALLINT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, RSRP_COLUMN, GeoPackageDataType.FLOAT, false, null));
        tblcols.add(FeatureColumn.createColumn(colNum++, RSRQ_COLUMN, GeoPackageDataType.FLOAT, false, null));
        //noinspection UnusedAssignment
        tblcols.add(FeatureColumn.createColumn(colNum++, TA_COLUMN, GeoPackageDataType.SMALLINT, false, null));

        FeatureTable table = new FeatureTable(SurveyRecordWriter.LTE_RECORDS_TABLE_NAME, tblcols);
        geoPackage.createFeatureTable(table);

        contentsDao.create(contents);

        GeometryColumnsDao geometryColumnsDao = geoPackage.getGeometryColumnsDao();

        GeometryColumns geometryColumns = new GeometryColumns();
        geometryColumns.setContents(contents);
        geometryColumns.setColumnName(GEOMETRY_COLUMN);
        geometryColumns.setGeometryType(GeometryType.POINT);
        geometryColumns.setSrs(srs);
        geometryColumns.setZ((byte) 0); // TODO I am not sure if all of this is right
        geometryColumns.setM((byte) 0);
        geometryColumnsDao.create(geometryColumns);
    }

    /**
     * Convert the serving cell {@link CellInfoLte} object into an {@link LteRecord}, and update the UI with
     * the latest cell details.
     *
     * @param cellInfoLte The LTE serving cell details.
     * @return True if the LTE serving cell record is valid, false otherwise.
     */
    private boolean parseServingCellInfo(CellInfoLte cellInfoLte)
    {
        final LteRecord lteSurveyRecord = generateLteSurveyRecord(cellInfoLte);
        if (lteSurveyRecord == null)
        {
            return false;
        }

        writeSurveyRecordEntryToLogFile(lteSurveyRecord);
        updateUi(lteSurveyRecord);
        surveyRecordListeners.forEach(listener -> listener.onLteSurveyRecord(lteSurveyRecord));
        return true;
    }

    /**
     * Convert the neighbor cell {@link CellInfoLte} object into an {@link LteRecord}.
     *
     * @param cellInfoLte The LTE neighbor cell details.
     */
    private void parseNeighborCellInfo(CellInfoLte cellInfoLte)
    {
        //Log.v(LOG_TAG, "LTE Neighbor Cell : " + cellInfoLte.getCellIdentity().toString() + "\n LTE Neighbor Signal Values: " + cellInfoLte.getCellSignalStrength().toString());

        if (loggingEnabled)
        {
            final LteRecord lteSurveyRecord = generateLteSurveyRecord(cellInfoLte);
            if (lteSurveyRecord != null)
            {
                writeSurveyRecordEntryToLogFile(lteSurveyRecord);
            }
        }
    }

    /**
     * Given a {@link CellInfoLte} object, pull out the values and generate an {@link LteRecord}.
     *
     * @param cellInfoLte The object that contains the LTE Cell info.  This can be a serving cell,
     *                    or a neighbor cell.
     * @return The survey record.
     */
    private LteRecord generateLteSurveyRecord(CellInfoLte cellInfoLte)
    {
        final CellIdentityLte cellIdentity = cellInfoLte.getCellIdentity();
        final int mcc = cellIdentity.getMcc();
        final int mnc = cellIdentity.getMnc();
        final int tac = cellIdentity.getTac();
        final int ci = cellIdentity.getCi();
        final int earfcn = cellIdentity.getEarfcn();
        final int pci = cellIdentity.getPci();

        CellSignalStrengthLte cellSignalStrengthLte = cellInfoLte.getCellSignalStrength();
        final int rsrp = cellSignalStrengthLte.getRsrp();
        final int rsrq = cellSignalStrengthLte.getRsrq();
        final int timingAdvance = cellSignalStrengthLte.getTimingAdvance();

        // Validate that the required fields are present before proceeding further
        if (!validate(earfcn, pci, rsrp)) return null;

        final LteRecord.Builder lteRecordBuilder = LteRecord.newBuilder();

        if (locationManager != null)
        {
            @SuppressLint("MissingPermission") final Location lastKnownLocation = locationManager.getLastKnownLocation(bestProvider);
            if (lastKnownLocation != null)
            {
                lteRecordBuilder.setLatitude(lastKnownLocation.getLatitude());
                lteRecordBuilder.setLongitude(lastKnownLocation.getLongitude());
                lteRecordBuilder.setAltitude((float) lastKnownLocation.getAltitude());
            }
        }

        lteRecordBuilder.setDeviceSerialNumber(deviceId);
        lteRecordBuilder.setDeviceTime(System.currentTimeMillis());
        lteRecordBuilder.setMissionId(missionId);
        lteRecordBuilder.setRecordNumber(recordNumber++);
        lteRecordBuilder.setGroupNumber(groupNumber);

        if (mcc != Integer.MAX_VALUE) lteRecordBuilder.setMcc(Int32Value.newBuilder().setValue(mcc).build());
        if (mnc != Integer.MAX_VALUE) lteRecordBuilder.setMnc(Int32Value.newBuilder().setValue(mnc).build());
        if (tac != Integer.MAX_VALUE) lteRecordBuilder.setTac(Int32Value.newBuilder().setValue(tac).build());
        if (ci != Integer.MAX_VALUE) lteRecordBuilder.setCi(Int32Value.newBuilder().setValue(ci).build());
        if (earfcn != Integer.MAX_VALUE) lteRecordBuilder.setEarfcn(Int32Value.newBuilder().setValue(earfcn).build());
        if (pci != Integer.MAX_VALUE) lteRecordBuilder.setPci(Int32Value.newBuilder().setValue(pci).build());
        if (rsrp != Integer.MAX_VALUE) lteRecordBuilder.setRsrp(Int32Value.newBuilder().setValue(rsrp).build());
        if (rsrq != Integer.MAX_VALUE) lteRecordBuilder.setRsrq(Int32Value.newBuilder().setValue(rsrq).build());
        if (timingAdvance != Integer.MAX_VALUE) lteRecordBuilder.setTa(Int32Value.newBuilder().setValue(timingAdvance).build());

        return lteRecordBuilder.build();
    }

    /**
     * Validates the required fields.
     *
     * @return True if the provided fields are all valid, false if one or more is invalid.
     */
    private boolean validate(int earfcn, int pci, int rsrp)
    {
        if (earfcn == Integer.MAX_VALUE)
        {
            Log.v(LOG_TAG, "The EARFCN is required to build an LTE Survey Record.");
            return false;
        }

        if (pci == Integer.MAX_VALUE)
        {
            Log.v(LOG_TAG, "The PCI is required to build an LTE Survey Record.");
            return false;
        }

        if (rsrp == Integer.MAX_VALUE)
        {
            Log.v(LOG_TAG, "The RSRP is required to build an LTE Survey Record.");
            return false;
        }

        return true;
    }

    private void updateUi(LteRecord lteSurveyRecord)
    {
        if (!networkSurveyActivity.isNetworkDetailsVisible())
        {
            Log.v(LOG_TAG, "Skipping updating the Network Details UI because it is not visible");
            return;
        }

        setText(R.id.mccValue, lteSurveyRecord.hasMcc() ? String.valueOf(lteSurveyRecord.getMcc().getValue()) : "");
        setText(R.id.mncValue, lteSurveyRecord.hasMnc() ? String.valueOf(lteSurveyRecord.getMnc().getValue()) : "");
        setText(R.id.tacValue, lteSurveyRecord.hasTac() ? String.valueOf(lteSurveyRecord.getTac().getValue()) : "");

        if (lteSurveyRecord.hasCi())
        {
            final int ci = lteSurveyRecord.getCi().getValue();
            setText(R.id.cidValue, String.valueOf(ci));

            // The Cell Identity is 28 bits long. The first 20 bits represent the Macro eNodeB ID. The last 8 bits
            // represent the sector.  Strip off the last 8 bits to get the Macro eNodeB ID.
            int eNodebId = ci >> 8;
            setText(R.id.enbIdValue, String.valueOf(eNodebId));

            int sectorId = ci & 0xFF;
            setText(R.id.sectorIdValue, String.valueOf(sectorId));
        }

        setText(R.id.earfcnValue, lteSurveyRecord.hasEarfcn() ? String.valueOf(lteSurveyRecord.getEarfcn().getValue()) : "");

        if (lteSurveyRecord.hasPci())
        {
            final int pci = lteSurveyRecord.getPci().getValue();
            int primarySyncSequence = pci % 3;
            int secondarySyncSequence = pci / 3;
            setText(R.id.pciValue, pci + " (" + primarySyncSequence + "/" + secondarySyncSequence + ")");
        }

        checkAndSetLocation(lteSurveyRecord);

        setText(R.id.rsrpValue, lteSurveyRecord.hasRsrp() ? String.valueOf(lteSurveyRecord.getRsrp().getValue()) : "");
        setText(R.id.rsrqValue, lteSurveyRecord.hasRsrq() ? String.valueOf(lteSurveyRecord.getRsrq().getValue()) : "");
        setText(R.id.taValue, lteSurveyRecord.hasTa() ? String.valueOf(lteSurveyRecord.getTa().getValue()) : "");
    }

    /**
     * Sets the provided text on the TextView with the provided Text View ID.
     *
     * @param textViewId The ID that is used to lookup the TextView.
     * @param text       The text to set on the text view.
     */
    private void setText(int textViewId, String text)
    {
        ((TextView) networkSurveyActivity.findViewById(textViewId)).setText(text);
    }

    /**
     * Checks to make sure the location is not null, and then updates the appropriate UI elements.
     *
     * @param lteRecord The LTE Record to check and see if it has a valid location.
     */
    private void checkAndSetLocation(LteRecord lteRecord)
    {
        final double latitude = lteRecord.getLatitude();
        final double longitude = lteRecord.getLongitude();
        if (latitude == 0 && longitude == 0)
        {
            setText(R.id.latitudeValue, "");
            setText(R.id.longitudeValue, "");
        } else
        {
            setText(R.id.latitudeValue, String.valueOf(latitude));
            setText(R.id.longitudeValue, String.valueOf(longitude));
        }
    }

    private void writeSurveyRecordEntryToLogFile(final LteRecord lteSurveyRecord)
    {
        if (!loggingEnabled)
        {
            Log.v(LOG_TAG, "Not writing the log file because logging is turned off");
            return;
        }

        handler.post(new Runnable()
        {
            public void run()
            {
                try
                {
                    if (geoPackage != null)
                    {
                        FeatureDao featureDao = geoPackage.getFeatureDao(LTE_RECORDS_TABLE_NAME);
                        FeatureRow row = featureDao.newRow();

                        Point fix = new Point(lteSurveyRecord.getLongitude(), lteSurveyRecord.getLatitude(), (double) lteSurveyRecord.getAltitude());

                        GeoPackageGeometryData geomData = new GeoPackageGeometryData(WGS84_SRS);
                        geomData.setGeometry(fix);

                        row.setGeometry(geomData);

                        row.setValue(TIME_COLUMN, lteSurveyRecord.getDeviceTime());
                        row.setValue(RECORD_NUMBER_COLUMN, lteSurveyRecord.getRecordNumber());
                        row.setValue(GROUP_NUMBER_COLUMN, lteSurveyRecord.getGroupNumber());

                        if (lteSurveyRecord.hasMcc()) setShortValue(row, MCC_COLUMN, lteSurveyRecord.getMcc().getValue());
                        if (lteSurveyRecord.hasMnc()) setShortValue(row, MNC_COLUMN, lteSurveyRecord.getMnc().getValue());
                        if (lteSurveyRecord.hasTac()) setIntValue(row, TAC_COLUMN, lteSurveyRecord.getTac().getValue());
                        if (lteSurveyRecord.hasCi()) setIntValue(row, CI_COLUMN, lteSurveyRecord.getCi().getValue());
                        if (lteSurveyRecord.hasEarfcn()) setIntValue(row, EARFCN_COLUMN, lteSurveyRecord.getEarfcn().getValue());
                        if (lteSurveyRecord.hasPci()) setShortValue(row, PCI_COLUMN, lteSurveyRecord.getPci().getValue());
                        if (lteSurveyRecord.hasRsrp()) setFloatValue(row, RSRP_COLUMN, lteSurveyRecord.getRsrp().getValue());
                        if (lteSurveyRecord.hasRsrq()) setFloatValue(row, RSRQ_COLUMN, lteSurveyRecord.getRsrq().getValue());
                        if (lteSurveyRecord.hasTa()) setShortValue(row, TA_COLUMN, lteSurveyRecord.getTa().getValue());

                        featureDao.insert(row);
                    }
                } catch (Exception e)
                {
                    Log.e(LOG_TAG, "Something went wrong when trying to write an LTE survey record", e);
                }
            }
        });
    }

    /**
     * Sets the provided value on the row at the specified column as an int ({@link GeoPackageDataType#MEDIUMINT}).
     *
     * @param featureRow The row to populate the value.
     * @param columnName The column to set the value in.
     * @param value      The value to set as an int.
     */
    private void setIntValue(FeatureRow featureRow, String columnName, int value)
    {
        featureRow.setValue(columnName, value);
    }

    /**
     * Sets the provided value on the row at the specified column as a short ({@link GeoPackageDataType#SMALLINT}).
     *
     * @param featureRow The row to populate the value.
     * @param columnName The column to set the value in.
     * @param value      The value to set as a short.
     */
    private void setShortValue(FeatureRow featureRow, String columnName, int value)
    {
        featureRow.setValue(columnName, (short) value);
    }

    /**
     * Sets the privded value on the row at the specified column as a float ({@link GeoPackageDataType#FLOAT}).
     *
     * @param featureRow The row to populate the value.
     * @param columnName The column to set the value in.
     * @param value      The value to set as a float.
     */
    private void setFloatValue(FeatureRow featureRow, String columnName, int value)
    {
        featureRow.setValue(columnName, (float) value);
    }

    /**
     * Checks to see if we can write to the external storage area.  It might be unavailable if the
     * storage is connected to a computer.
     *
     * @return True if available, false if the storage volume is not able to be written to.
     */
    private boolean isExternalStorageWritable()
    {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    private File getPrivateStorageFile(Context context, String fileName)
    {
        return new File(context.getExternalFilesDir(null), fileName);
    }

    private File getPublicStorageDir(String fileName)
    {
        File file = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), LOG_DIRECTORY_NAME + "/" + fileName);
        /*if (!file.mkdirs())
        {
            Log.e(LOG_TAG, "The Network Survey Data Directory could not be created");
        }*/

        return file;
    }

    private String createPublicStorageFilePath()
    {
        return Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS) + "/" + LOG_DIRECTORY_NAME + "/" +
                FILE_NAME_PREFIX + "-" + formatFilenameFriendlyTime.format(System.currentTimeMillis()) + ".gpkg";
    }
}
