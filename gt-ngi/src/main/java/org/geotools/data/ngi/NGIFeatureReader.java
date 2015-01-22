/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2012, Open Source Geospatial Foundation (OSGeo)
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
package org.geotools.data.ngi;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

/**
 * NGI FeatureReader
 * 
 * @author MapPlus, mapplus@gmail.com, http://onspatial.com
 * @since 2012-10-30
 * @see
 * 
 */
public class NGIFeatureReader implements FeatureReader<SimpleFeatureType, SimpleFeature> {
    protected static final Logger LOGGER = Logging.getLogger(NGIFeatureReader.class);

    private NGIReader reader;
    
    public NGIFeatureReader(NGIReader reader, SimpleFeatureType featureType) {
        this(reader, featureType,  Query.ALL);
    }
    
    public NGIFeatureReader(NGIReader reader, SimpleFeatureType featureType, Query query) {
        this.reader = reader;
        this.reader.setSchema(featureType);
        this.reader.setQuery(query);
    }

    public SimpleFeatureType getFeatureType() {
        return reader.getSchema();
    }

    public SimpleFeature next() throws IOException, IllegalArgumentException,
            NoSuchElementException {
        return reader.next();
    }

    public boolean hasNext() throws IOException {
        return reader.hasNext();
    }

    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

}
