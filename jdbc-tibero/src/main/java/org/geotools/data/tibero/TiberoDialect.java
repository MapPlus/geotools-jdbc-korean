/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.tibero;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.jdbc.BasicSQLDialect;
import org.geotools.jdbc.ColumnMetadata;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.referencing.CRS;
import org.geotools.util.Version;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

public class TiberoDialect extends BasicSQLDialect {

    boolean looseBBOXEnabled = false;

    boolean estimatedExtentsEnabled = false;

    Version version;

    // for compatibility
    boolean isOldDBVersion = false;

    private static final String SYS_GIS = "SYSGIS";

    // gis view/table
    private enum GisTable {
        // spatial ref sys metadata
        SPATIAL_REF_SYS_BASE,
        SPATIAL_REF_SYS,

        // geometry columns metadata
        GEOMETRY_COLUMNS_BASE,
        GEOMETRY_COLUMNS,
        ALL_GEOMETRY_COLUMNS,
        USER_GEOMETRY_COLUMNS,

        // units of measure
        UNITS_OF_MEASURE,
        AREA_UNITS,
        DIST_UNITS,
        ANGLE_UNITS;
    }

    @SuppressWarnings({ "rawtypes", "serial" })
    final static Map<String, Class> TYPE_TO_CLASS_MAP = new HashMap<String, Class>() {
        {
            put("GEOMETRY", Geometry.class);
            put("POINT", Point.class);
            put("POINTM", Point.class);
            put("LINESTRING", LineString.class);
            put("LINESTRINGM", LineString.class);
            put("POLYGON", Polygon.class);
            put("POLYGONM", Polygon.class);
            put("MULTIPOINT", MultiPoint.class);
            put("MULTIPOINTM", MultiPoint.class);
            put("MULTILINESTRING", MultiLineString.class);
            put("MULTILINESTRINGM", MultiLineString.class);
            put("MULTIPOLYGON", MultiPolygon.class);
            put("MULTIPOLYGONM", MultiPolygon.class);
            put("GEOMETRYCOLLECTION", GeometryCollection.class);
            put("GEOMETRYCOLLECTIONM", GeometryCollection.class);
            put("BYTEA", byte[].class);
        }
    };

    @SuppressWarnings({ "rawtypes", "serial" })
    final static Map<Class, String> CLASS_TO_TYPE_MAP = new HashMap<Class, String>() {
        {
            put(Geometry.class, "GEOMETRY");
            put(Point.class, "POINT");
            put(LineString.class, "LINESTRING");
            put(Polygon.class, "POLYGON");
            put(MultiPoint.class, "MULTIPOINT");
            put(MultiLineString.class, "MULTILINESTRING");
            put(MultiPolygon.class, "MULTIPOLYGON");
            put(GeometryCollection.class, "GEOMETRYCOLLECTION");
            put(byte[].class, "BYTEA");
        }
    };

    public TiberoDialect(JDBCDataStore dataStore) {
        super(dataStore);
    }

    public boolean isLooseBBOXEnabled() {
        return looseBBOXEnabled;
    }

    public void setLooseBBOXEnabled(boolean looseBBOXEnabled) {
        this.looseBBOXEnabled = looseBBOXEnabled;
    }

    public boolean isEstimatedExtentsEnabled() {
        return estimatedExtentsEnabled;
    }

    public void setEstimatedExtentsEnabled(boolean estimatedExtentsEnabled) {
        this.estimatedExtentsEnabled = estimatedExtentsEnabled;
    }

    @Override
    public boolean isAggregatedSortSupported(String function) {
        return "distinct".equalsIgnoreCase(function);
    }

    @Override
    public void initializeConnection(Connection cx) throws SQLException {
        super.initializeConnection(cx);

        /**
         * as DB version upgraded,
         * SYSGIS.GEOMETRY_COLUMNS_BASE table
         * and F_GEOMETRY_TYPE column of SYSGIS.ALL_GEOMETRY_COLUMNS view
         * was deleted
         */
        isOldDBVersion = doesTableExist(cx, SYS_GIS, GisTable.GEOMETRY_COLUMNS_BASE.name())
                && doesColumnExist(cx, SYS_GIS, GisTable.ALL_GEOMETRY_COLUMNS.name(), "F_GEOMETRY_TYPE");
    }

    private boolean doesTableExist(Connection cx, String schemaName, String tableName) throws SQLException {
        DatabaseMetaData metaData = cx.getMetaData();
        try (ResultSet rs = metaData.getTables(null, schemaName, tableName, null)) {
            return rs.next();
        }
    }

    private boolean doesColumnExist(Connection cx, String schemaName, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = cx.getMetaData();
        try (ResultSet rs = metaData.getColumns(null, schemaName, tableName, columnName)){
            return rs.next();
        }
    }

    @Override
    public boolean includeTable(String schemaName, String tableName, Connection cx)
            throws SQLException {
        // check if related to gis view/table
        for (GisTable gisTable : GisTable.values()) {
            if (tableName.equalsIgnoreCase(gisTable.name())) {
                return false;
            }
        }

        // tables in geometry_columns
        if (schemaName == null || schemaName.isEmpty()) {
            schemaName = SYS_GIS;
        }

        StringBuffer sql = new StringBuffer();
        sql.append("SELECT F_TABLE_NAME FROM ").append(GisTable.GEOMETRY_COLUMNS.name());
        sql.append(" WHERE F_TABLE_SCHEMA = '").append(schemaName).append("'");
        sql.append(" AND F_TABLE_NAME = '").append(tableName).append("'");

        Statement statement = null;
        ResultSet result = null;
        try {
            statement = cx.createStatement();
            result = statement.executeQuery(sql.toString());
            if (result.next()) {
                return true;
            }
        } finally {
            dataStore.closeSafe(result);
            dataStore.closeSafe(statement);
        }

        // others?
        return false;
    }

    ThreadLocal<WKBAttributeIO> wkbReader = new ThreadLocal<WKBAttributeIO>();

    public Geometry decodeGeometryValue(GeometryDescriptor descriptor, ResultSet rs, String column,
            GeometryFactory factory, Connection cx) throws IOException, SQLException {
        WKBAttributeIO reader = getWKBReader(factory);
        return (Geometry) reader.read(rs, column);
    }

    public Geometry decodeGeometryValue(GeometryDescriptor descriptor, ResultSet rs, int column,
            GeometryFactory factory, Connection cx) throws IOException, SQLException {
        WKBAttributeIO reader = getWKBReader(factory);
        return (Geometry) reader.read(rs, column);
    }

    @Override
    public Geometry decodeGeometryValue(GeometryDescriptor descriptor, ResultSet rs, String column,
            GeometryFactory factory, Connection cx, Hints hints) throws IOException, SQLException {
        WKBAttributeIO reader = getWKBReader(factory);
        return (Geometry) reader.read(rs, column);
    }

    WKBAttributeIO getWKBReader(GeometryFactory factory) {
        WKBAttributeIO reader = wkbReader.get();
        if (reader == null) {
            reader = new WKBAttributeIO(factory);
            wkbReader.set(reader);
        } else {
            reader.setGeometryFactory(factory);
        }
        return reader;
    }

    @Override
    public void encodeGeometryColumn(GeometryDescriptor gatt, String prefix, int srid, Hints hints,
            StringBuffer sql) {
        sql.append(" ST_ASBINARY(");
        encodeColumnName(prefix, gatt.getLocalName(), sql);
        sql.append(")");
    }

    @Override
    public void encodeGeometryEnvelope(String tableName, String geometryColumn, StringBuffer sql) {
        sql.append(" ST_ASTEXT(ST_ENVELOPE(");
        encodeColumnName(null, geometryColumn, sql);
        sql.append("))");
    }

    @Override
    public void encodeColumnName(String prefix, String raw, StringBuffer sql) {
        if (prefix != null) {
            sql.append(ne()).append(prefix).append(ne()).append(".");
        }
        sql.append(ne()).append(raw).append(ne());
    }

    @Override
    public List<ReferencedEnvelope> getOptimizedBounds(String schema, SimpleFeatureType featureType,
            Connection cx) throws SQLException, IOException {
        if (!estimatedExtentsEnabled) {
            return null;
        }

        String tableName = featureType.getTypeName();
        if (dataStore.getVirtualTables().get(tableName) != null) {
            return null;
        }

        Statement st = null;
        ResultSet rs = null;

        List<ReferencedEnvelope> result = new ArrayList<ReferencedEnvelope>();
        Savepoint savePoint = null;
        try {
            st = cx.createStatement();
            if (!cx.getAutoCommit()) {
                savePoint = cx.setSavepoint();
            }

            GeometryDescriptor att = featureType.getGeometryDescriptor();
            String geometryField = att.getName().getLocalPart();

            // ===================Support: Tibero 5, 6, 7========================
            // SELECT MIN(ST_MINX(OBJ)), MIN(ST_MINY(OBJ)), MAX(ST_MAXX(OBJ)), MAX(ST_MAXY(OBJ)) FROM ROAD;
            // ==================================================================

            StringBuffer sql = new StringBuffer();
            sql.append("SELECT ");
            sql.append("  MIN(ST_MINX(\"").append(geometryField).append("\"))");
            sql.append(", MIN(ST_MINY(\"").append(geometryField).append("\"))");
            sql.append(", MAX(ST_MAXX(\"").append(geometryField).append("\"))");
            sql.append(", MAX(ST_MAXY(\"").append(geometryField).append("\"))");
            sql.append(" FROM \"").append(tableName).append("\"");

            rs = st.executeQuery(sql.toString());
            if (rs.next()) {
                CoordinateReferenceSystem crs = att.getCoordinateReferenceSystem();
                final double x1 = rs.getDouble(1);
                final double y1 = rs.getDouble(2);
                final double x2 = rs.getDouble(3);
                final double y2 = rs.getDouble(4);

                // reproject and merge
                result.add(new ReferencedEnvelope(x1, x2, y1, y2, crs));
            }
        } catch (SQLException e) {
            if (savePoint != null) {
                cx.rollback(savePoint);
            }
            LOGGER.log(Level.WARNING,
                    "Failed to search Extent, falling back on envelope aggregation", e);
            return null;
        } finally {
            if (savePoint != null) {
                cx.releaseSavepoint(savePoint);
            }
            dataStore.closeSafe(rs);
            dataStore.closeSafe(st);
        }
        return result;
    }

    @Override
    public Envelope decodeGeometryEnvelope(ResultSet rs, int column, Connection cx)
            throws SQLException, IOException {
        try {
            String envelope = rs.getString(column);
            if (envelope != null) {
                return new WKTReader().read(envelope).getEnvelopeInternal();
            } else {
                return new Envelope();
            }
        } catch (ParseException e) {
            throw (IOException) new IOException("Error occurred parsing the bounds WKT")
                    .initCause(e);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class<?> getMapping(ResultSet columnMetaData, Connection cx) throws SQLException {
        String typeName = columnMetaData.getString("TYPE_NAME");

        if ("uuid".equalsIgnoreCase(typeName)) {
            return UUID.class;
        }

        if ("citext".equalsIgnoreCase(typeName)) {
            return String.class;
        }

        String gType = null;
        if ("geometry".equalsIgnoreCase(typeName)) {
            gType = lookupGeometryType(columnMetaData, cx, GisTable.GEOMETRY_COLUMNS.name(), "F_GEOMETRY_COLUMN");
        } else {
            return null;
        }

        // decode the type into
        if (gType == null) {
            // it's either a generic geography or geometry not registered in the medatata tables
            return Geometry.class;
        } else {
            Class geometryClass = (Class) TYPE_TO_CLASS_MAP.get(gType.toUpperCase());
            if (geometryClass == null) {
                geometryClass = Geometry.class;
            }

            return geometryClass;
        }
    }

    String lookupGeometryType(ResultSet columnMetaData, Connection cx, String gTableName,
            String gColumnName) throws SQLException {
        // grab the information we need to proceed
        String tableName = columnMetaData.getString("TABLE_NAME");
        String columnName = columnMetaData.getString("COLUMN_NAME");
        String schemaName = columnMetaData.getString("TABLE_SCHEM");

        // first attempt, try with the geometry metadata
        Statement statement = null;
        ResultSet result = null;

        try {
            String typeStr = (isOldDBVersion)? "F_GEOMETRY_TYPE" : "TYPE";

            String sqlStatement = "SELECT " + typeStr + " FROM " + gTableName + " WHERE " //
                    + "F_TABLE_SCHEMA = '" + schemaName + "' " //
                    + "AND F_TABLE_NAME = '" + tableName + "' " //
                    + "AND " + gColumnName + " = '" + columnName + "'";

            LOGGER.log(Level.FINE, "Geometry type check; {0} ", sqlStatement);
            statement = cx.createStatement();
            result = statement.executeQuery(sqlStatement);

            if (result.next()) {
                return result.getString(1);
            }
        } finally {
            dataStore.closeSafe(result);
            dataStore.closeSafe(statement);
        }

        return null;
    }

    @Override
    public void handleUserDefinedType(ResultSet columnMetaData, ColumnMetadata metadata,
            Connection cx) throws SQLException {
        String schemaName = columnMetaData.getString("TABLE_SCHEM");

        String sql = "SELECT type_name FROM all_types " + " WHERE owner = '" + schemaName;

        LOGGER.fine(sql);

        Statement st = cx.createStatement();
        try {
            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    metadata.setTypeName(rs.getString(1));
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }
    }

    @Override
    public Integer getGeometrySRID(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {
        // first attempt, try with the geometry metadata
        Statement statement = null;
        ResultSet result = null;
        Integer srid = null;
        try {
            // try geometry_columns
            try {
                String sqlStatement = "SELECT SRID FROM GEOMETRY_COLUMNS WHERE " //
                        + "F_TABLE_SCHEMA = '" + schemaName + "' " //
                        + "AND F_TABLE_NAME = '" + tableName + "' " //
                        + "AND F_GEOMETRY_COLUMN = '" + columnName + "'";

                LOGGER.log(Level.FINE, "Geometry srid check; {0} ", sqlStatement);
                statement = cx.createStatement();
                result = statement.executeQuery(sqlStatement);

                if (result.next()) {
                    srid = result.getInt(1);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to retrieve information about " + schemaName + "."
                        + tableName + "." + columnName
                        + " from the geometry_columns table, checking the first geometry instead",
                        e);
            } finally {
                dataStore.closeSafe(result);
            }

        } finally {
            dataStore.closeSafe(result);
            dataStore.closeSafe(statement);
        }

        return srid;
    }

    @Override
    public int getGeometryDimension(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {
        // first attempt, try with the geometry metadata
        Statement statement = null;
        ResultSet result = null;
        int dimension = 2; // default
        try {
            // try geometry_columns
            try {
                String sqlStatement = "SELECT COORD_DIMENSION FROM GEOMETRY_COLUMNS WHERE " //
                        + "F_TABLE_SCHEMA = '" + schemaName + "' " //
                        + "AND F_TABLE_NAME = '" + tableName + "' " //
                        + "AND F_GEOMETRY_COLUMN = '" + columnName + "'";

                LOGGER.log(Level.FINE, "Geometry dimension check; {0} ", sqlStatement);
                statement = cx.createStatement();
                result = statement.executeQuery(sqlStatement);

                if (result.next()) {
                    dimension = result.getInt(1);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.WARNING, "Failed to retrieve information about " + schemaName + "."
                        + tableName + "." + columnName
                        + " from the geometry_columns table, checking the first geometry instead",
                        e);
            } finally {
                dataStore.closeSafe(result);
            }

        } finally {
            dataStore.closeSafe(result);
            dataStore.closeSafe(statement);
        }

        return dimension;
    }

    @Override
    public String getSequenceForColumn(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {
        return "seq_" + tableName + "_" + columnName;
    }

    private String getSpatialIdxName(String tableName, String columnName) {
        return "SPATIAL_" + tableName + "_" + columnName;
    }

    @Override
    public Object getNextSequenceValue(String schemaName, String sequenceName, Connection cx)
            throws SQLException {
        Statement st = cx.createStatement();
        try {
            String sql = "SELECT \"" + sequenceName + "\".NEXTVAL FROM DUAL";

            dataStore.getLogger().fine(sql);
            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }

        return null;
    }

    @Override
    public boolean lookupGeneratedValuesPostInsert() {
        return true;
    }

    @Override
    public Object getLastAutoGeneratedValue(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {

        Statement st = cx.createStatement();
        try {
            String sequenceName = getSequenceForColumn(schemaName, tableName, "fid", cx);
            String sql = "SELECT \"" + sequenceName + "\".CURRVAL FROM DUAL";
            dataStore.getLogger().fine(sql);

            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }

        return null;
    }

    @Override
    public Object getNextAutoGeneratedValue(String schemaName, String tableName, String columnName,
            Connection cx) throws SQLException {

        Statement st = cx.createStatement();
        try {
            String sequenceName = getSequenceForColumn(schemaName, tableName, "fid", cx);
            String sql = "SELECT \"" + sequenceName + "\".NEXTVAL FROM DUAL";
            dataStore.getLogger().fine(sql);

            ResultSet rs = st.executeQuery(sql);
            try {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } finally {
                dataStore.closeSafe(rs);
            }
        } finally {
            dataStore.closeSafe(st);
        }

        return null;
    }

    @Override
    public void registerClassToSqlMappings(Map<Class<?>, Integer> mappings) {
        super.registerClassToSqlMappings(mappings);

        // jdbc metadata for geom columns reports DATA_TYPE=1111=Types.OTHER
        mappings.put(Short.class, new Integer(Types.SMALLINT));
        mappings.put(Integer.class, new Integer(Types.INTEGER));
        mappings.put(Long.class, new Integer(Types.BIGINT));
        mappings.put(Float.class, new Integer(Types.REAL));
        mappings.put(Double.class, new Integer(Types.DOUBLE));
        mappings.put(Geometry.class, new Integer(Types.OTHER));
        mappings.put(UUID.class, Types.OTHER);
    }

    @Override
    public void registerSqlTypeNameToClassMappings(Map<String, Class<?>> mappings) {
        super.registerSqlTypeNameToClassMappings(mappings);

        mappings.put("GEOMETRY", Geometry.class);
        mappings.put("geometry", Geometry.class);
        mappings.put("text", String.class);
        mappings.put("uuid", UUID.class);
    }

    @Override
    public void registerSqlTypeToSqlTypeNameOverrides(Map<Integer, String> overrides) {
        overrides.put(new Integer(Types.VARCHAR), "VARCHAR");
        overrides.put(new Integer(Types.BOOLEAN), "BOOL");
        overrides.put(new Integer(Types.SMALLINT), "INTEGER");
        overrides.put(new Integer(Types.INTEGER), "INTEGER");
        overrides.put(new Integer(Types.BIGINT), "INTEGER");
        overrides.put(new Integer(Types.REAL), "FLOAT");
        overrides.put(new Integer(Types.FLOAT), "FLOAT");
        overrides.put(new Integer(Types.DOUBLE), "NUMBER");
        overrides.put(new Integer(Types.DECIMAL), "NUMBER");
        overrides.put(new Integer(Types.NUMERIC), "NUMBER");
    }

    @Override
    public String getGeometryTypeName(Integer type) {
        return "GEOMETRY"; // BLOB
    }

    @Override
    public void encodePrimaryKey(String column, StringBuffer sql) {
        encodeColumnName(null, column, sql);
        sql.append(" INTEGER PRIMARY KEY");
    }

    /**
     * Creates GEOMETRY_COLUMN registrations and spatial indexes for all geometry columns
     */
    @Override
    public void postCreateTable(String schemaName, SimpleFeatureType featureType, Connection cx)
            throws SQLException {
        String tableName = featureType.getName().getLocalPart();

        Statement st = null;
        try {
            st = cx.createStatement();

            // register all geometry columns in the database
            for (AttributeDescriptor att : featureType.getAttributeDescriptors()) {
                if (att instanceof GeometryDescriptor) {
                    GeometryDescriptor gd = (GeometryDescriptor) att;

                    String columnName = gd.getLocalName();

                    // lookup or reverse engineer the srid
                    int srid = 101; // default value

                    if (gd.getUserData().get(JDBCDataStore.JDBC_NATIVE_SRID) != null) {
                        srid = (Integer) gd.getUserData().get(JDBCDataStore.JDBC_NATIVE_SRID);
                    } else if (gd.getCoordinateReferenceSystem() != null) {
                        try {
                            Integer result = CRS.lookupEpsgCode(gd.getCoordinateReferenceSystem(),
                                    true);
                            if (result != null) {
                                srid = result;
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "Error looking up the "
                                    + "epsg code for metadata " + "insertion, assuming -1", e);
                        }
                    }

                    // assume 2 dimensions, but ease future customization
                    int dimensions = 2;
                    if (gd.getUserData().get(Hints.COORDINATE_DIMENSION) != null) {
                        dimensions = (Integer) gd.getUserData().get(Hints.COORDINATE_DIMENSION);
                    }

                    // grab the geometry type
                    String geomType = CLASS_TO_TYPE_MAP.get(gd.getType().getBinding());
                    if (geomType == null) {
                        geomType = "GEOMETRY";
                    }

                    String sql = "";
                    if (isOldDBVersion) {
                        // register the geometry type, first remove and eventual
                        // leftover, then write out the real one
                        sql = "DELETE FROM " + SYS_GIS + "." + GisTable.GEOMETRY_COLUMNS_BASE.name() //
                                + " WHERE F_TABLE_SCHEMA = '" + schemaName + "'" //
                                + " AND F_TABLE_NAME = '" + tableName + "'" //
                                + " AND F_GEOMETRY_COLUMN = '" + columnName + "'";
                        LOGGER.fine(sql);
                        st.execute(sql);

                        sql = "INSERT INTO " + SYS_GIS + "." + GisTable.GEOMETRY_COLUMNS_BASE.name() + " VALUES (" //
                                + "'" + schemaName + "'," //
                                + "'" + tableName + "'," //
                                + "'" + columnName + "'," //
                                + dimensions + "," //
                                + srid + "," //
                                + "'" + geomType + "', '')";
                        LOGGER.fine(sql);
                        st.execute(sql);
                    }

                    // drop spatial index
                    // DROP INDEX INDEX_NAME;
                    String idxName = getSpatialIdxName(tableName, columnName);
                    sql = "DROP INDEX \"" + idxName + "\"";
                    LOGGER.fine(sql);
                    try {
                        st.execute(sql);
                    } catch (Exception e) {
                        LOGGER.fine(e.getMessage());
                    }

                    // add the spatial index
                    // CREATE INDEX IDX_STORES_GEOMETRY ON SYSGIS.STORES("the_geom") RTREE;
                    sql = "CREATE INDEX \"" + idxName + "\"" //
                            + " ON " //
                            + "\"" + tableName + "\"" //
                            + " (" //
                            + "\"" + columnName + "\"" //
                            + ") RTREE";
                    LOGGER.fine(sql);
                    st.execute(sql);

                    // create sequence
                    String sequenceName = getSequenceForColumn(schemaName, tableName, "fid", cx);
                    sql = "DROP SEQUENCE \"" + sequenceName + "\"";
                    LOGGER.fine(sql);
                    try {
                        st.execute(sql);
                    } catch (Exception e) {
                        LOGGER.fine(e.getMessage());
                    }

                    // CREATE SEQUENCE seq_building_fid START WITH 1 INCREMENT BY 1 MINVALUE 1
                    // NOMAXVALUE
                    // INSERT INTO SEQTBL VALUES(seq1.NEXTVAL);
                    sql = "CREATE SEQUENCE \"" + sequenceName + "\"" //
                            + " START WITH 1 INCREMENT BY 1 MINVALUE 1 NOMAXVALUE";
                    LOGGER.fine(sql);
                    st.execute(sql);
                }
            }
            cx.commit();
        } finally {
            dataStore.closeSafe(st);
        }
    }

    @Override
    public void postDropTable(String schemaName, SimpleFeatureType featureType, Connection cx)
            throws SQLException {
        Statement st = cx.createStatement();

        try {
            if (isOldDBVersion) {
                String tableName = featureType.getTypeName();
                // remove all the geometry_column entries
                String sql = "DELETE FROM " + SYS_GIS + "." + GisTable.GEOMETRY_COLUMNS_BASE.name() //
                        + " WHERE F_TABLE_SCHEMA = '" + schemaName + "'" //
                        + " AND F_TABLE_NAME = '" + tableName + "'";
                LOGGER.fine(sql);
                st.execute(sql);
            }
        } finally {
            dataStore.closeSafe(st);
        }
    }

    @Override
    public void encodeGeometryValue(Geometry value, int dimension, int srid, StringBuffer sql)
            throws IOException {
        if (value == null) {
            sql.append("NULL");
        } else {
            if (value instanceof LinearRing) {
                // WKT does not support linear rings
                value = value.getFactory()
                        .createLineString(((LinearRing) value).getCoordinateSequence());
            }

            sql.append("ST_GEOMFROMTEXT('" + value.toText() + "')");
        }
    }

    @Override
    public FilterToSQL createFilterToSQL() {
        TiberoFilterToSQL sql = new TiberoFilterToSQL(this);
        sql.setLooseBBOXEnabled(looseBBOXEnabled);
        return sql;
    }

    @Override
    public boolean isLimitOffsetSupported() {
        return true;
    }

    @Override
    public void applyLimitOffset(StringBuffer sql, int limit, int offset) {
        // see http://progcookbook.blogspot.com/2006/02/using-rownum-properly-for-pagination.html
        // and http://www.oracle.com/technology/oramag/oracle/07-jan/o17asktom.html
        // to understand why we are going thru such hoops in order to get it working
        // The same technique is used in Hibernate to support pagination

        if (offset == 0) {
            sql.insert(0, "SELECT * FROM (");
            sql.append(") WHERE ROWNUM <= ").append(limit);
        } else {
            long max = (limit == Integer.MAX_VALUE ? Long.MAX_VALUE : limit + offset);
            sql.insert(0, "SELECT * FROM (SELECT A.*, ROWNUM RNUM FROM ( ");
            sql.append(") A WHERE ROWNUM <= ").append(max).append(")");
            sql.append("WHERE RNUM > ").append(offset);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void encodeValue(Object value, Class type, StringBuffer sql) {
        if (byte[].class.equals(type)) {
            // escape the into bytea representation
            StringBuffer sb = new StringBuffer();
            byte[] input = (byte[]) value;
            for (int i = 0; i < input.length; i++) {
                byte b = input[i];
                if (b == 0) {
                    sb.append("\\\\000");
                } else if (b == 39) {
                    sb.append("\\'");
                } else if (b == 92) {
                    sb.append("\\\\134'");
                } else if (b < 31 || b >= 127) {
                    sb.append("\\\\");
                    String octal = Integer.toOctalString(b);
                    if (octal.length() == 1) {
                        sb.append("00");
                    } else if (octal.length() == 2) {
                        sb.append("0");
                    }
                    sb.append(octal);
                } else {
                    sb.append((char) b);
                }
            }
            super.encodeValue(sb.toString(), String.class, sql);
        } else {
            super.encodeValue(value, type, sql);
        }
    }

    @Override
    public int getDefaultVarcharSize() {
        return 255;
    }

    @Override
    public String[] getDesiredTablesType() {
        return new String[] { "TABLE", "VIEW", "MATERIALIZED VIEW", "SYNONYM" };
    }

    @Override
    public void encodePostColumnCreateTable(AttributeDescriptor att, StringBuffer sql) {
        // encodePostColumnCreateTable
    }

    @Override
    public void postCreateAttribute(AttributeDescriptor att, String tableName, String schemaName,
            Connection cx) throws SQLException {
        // postCreateAttribute
    }

    @Override
    public void postCreateFeatureType(SimpleFeatureType featureType, DatabaseMetaData metadata,
            String schemaName, Connection cx) throws SQLException {
        // postCreateFeatureType
    }

    /**
     * Returns the Tibero version
     * 
     * @return
     */
    public Version getVersion(Connection conn) throws SQLException {
        if (version == null) {
            version = new Version("V_5_0_0"); // Minimum Version

            Statement st = null;
            ResultSet rs = null;
            try {
                st = conn.createStatement();
                rs = st.executeQuery("SELECT * FROM v$version WHERE NAME = 'PRODUCT_MAJOR'");
                if (rs.next()) {
                    version = new Version(rs.getString(1));
                }
            } finally {
                dataStore.closeSafe(rs);
                dataStore.closeSafe(st);
            }
        }
        return version;
    }

    /**
     * Returns true if the Tibero version is >= x.x
     */
    boolean supportsGeography(Connection cx) throws SQLException {
        return false; // getVersion(cx).compareTo(V_5_0_0) >= 0;
    }

}
