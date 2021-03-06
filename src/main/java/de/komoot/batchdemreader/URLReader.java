package de.komoot.batchdemreader;

import com.google.common.util.concurrent.Striped;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.index.ItemVisitor;
import com.vividsolutions.jts.index.strtree.STRtree;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.Interpolator2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.opengis.coverage.PointOutsideCoverageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.media.jai.Interpolation;
import java.awt.geom.Point2D;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.zip.GZIPInputStream;

/**
 * DEM file reader where all files are located in a (remote http) folder.
 *
 * @author jan
 */
public class URLReader implements DemReader {
    private static final Logger logger = LoggerFactory.getLogger(URLReader.class);
    private static final double TOLERANCE = 0.000001;
    private final URL demSourceBase;
    private final File cacheDirectory;
    private final Map<String, GridCoverage2D> openReaders = new ConcurrentHashMap<>();
    private final Striped<Lock> filenameLock = Striped.lock(1000);
    private STRtree index = new STRtree();

    /**
     * Creates a new reader using the given cache directory
     *
     * @param demSourceBase  source url of the DEM files
     * @param cacheDirectory local cache directory
     * @throws IOException
     */
    public URLReader(URL demSourceBase, File cacheDirectory) throws IOException {
        this.demSourceBase = demSourceBase;
        this.cacheDirectory = cacheDirectory;
        this.cacheDirectory.mkdir();

        try (InputStream stream = new GZIPInputStream(new URL(demSourceBase, "index.list.gz").openStream())) {
            populateIndex(stream);
        }
    }

    /**
     * Creates a new reader using the system temp directory as cache.
     *
     * @param demSourceBase source url of the DEM files
     * @throws IOException
     */
    public URLReader(URL demSourceBase) throws IOException {
        this(demSourceBase, new File(System.getProperty("java.io.tmpdir"), "dem"));
    }

    /**
     * Reads the value at the given coordinate.
     *
     * @param c a coordinate
     * @return the elevation value or {@link Double#NaN} if no data is available
     */
    @Override
    public double getValueAt(@Nonnull Coordinate c) {
        String filename = getCoverageFilename(c);
        if (filename == null) {
            return Double.NaN;
        }

        Lock lock = filenameLock.get(filename);
        try {
            lock.lock();
            GridCoverage2D coverage = findCoverage(filename);
            return extractValue(c, coverage);
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    private String getCoverageFilename(@Nonnull Coordinate c) {
        FirstEnvelopeFinder fmf = new FirstEnvelopeFinder(c);
        index.query(new Envelope(c), fmf);
        return fmf.getFirstMatch();
    }

    @Nonnull
    private GridCoverage2D findCoverage(@Nonnull String filename) {
        GridCoverage2D coverage = openReaders.get(filename);
        if (coverage == null) {
            String extractedFilename = filename.replace(".bz2", "");
            File localCachedFile = new File(cacheDirectory, extractedFilename);
            if (localCachedFile.exists() == false) {
                try (InputStream in = new URL(demSourceBase, filename).openStream()) {
                    File downloadFile = new File(cacheDirectory, extractedFilename + ".download");
                    try (OutputStream out = new FileOutputStream(downloadFile)) {
                        logger.info("Downloading {}/{}", demSourceBase, filename);
                        IOUtils.copy(new BZip2CompressorInputStream(in), out);
                        // rename after extracting. We don't want to leave broken files here.
                        boolean success = downloadFile.renameTo(localCachedFile);
                        if(!success) {
                            downloadFile.delete();
                            throw new RuntimeException("Unable to rename " + downloadFile + " to " + localCachedFile);
                        }
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException("Could not open temp file " + downloadFile, e);
                    } catch (IOException e) {
                        throw new RuntimeException("Could not open or write " + filename, e);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not open source url" + filename, e);
                }
            }
            //open previously extracted file

            try {
                logger.debug("Opening reader {}", localCachedFile);
                coverage = openReader(localCachedFile);
                openReaders.put(filename, coverage);
            } catch (IOException e) {
                throw new RuntimeException("Could not open file " + localCachedFile, e);
            }
        }
        return coverage;
    }

    static double extractValue(@Nonnull Coordinate c, @Nonnull GridCoverage2D coverage) {
        Point2D.Double p = new Point2D.Double(c.x, c.y);
        double[] r = new double[1];
        try {
            return coverage.evaluate(p, r)[0];
        } catch (PointOutsideCoverageException e) {
            throw new RuntimeException(String.format(Locale.US, "For %s (%s) (%f,%f): %s", coverage.getName(), coverage.getEnvelope(), c.x, c.y, e.getMessage()), e);
        }
    }

    private void populateIndex(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("#") == false) {
                String[] parts = line.split(",");
                if (parts.length >= 5) {
                    String filename = parts[0];
                    double minx = Double.parseDouble(parts[1]) + TOLERANCE;
                    double miny = Double.parseDouble(parts[2]) - TOLERANCE;
                    double maxx = Double.parseDouble(parts[3]) + TOLERANCE;
                    double maxy = Double.parseDouble(parts[4]) - TOLERANCE;
                    Envelope e = new Envelope(minx, maxx, miny, maxy);
                    index.insert(e, new IndexEntry(e, filename));
                }
            }
        }
        index.build();
    }

    @Override
    public void close() throws Exception {
        openReaders.values().forEach(reader -> reader.dispose(true));
    }

    synchronized static GridCoverage2D openReader(File f) throws IOException {
        GeoTiffReader reader = new GeoTiffReader(f);

        GridCoverage2D c = reader.read(null);
        GridCoverage2D coverage = Interpolator2D.create(c, Interpolation.getInstance(Interpolation.INTERP_BILINEAR));

        int dims = coverage.getNumSampleDimensions();
        if (dims != 1) {
            throw new IOException("Given coverage has " + dims + " dimensions but elevation data should have only one!");
        }
        return coverage;
    }

    private static class IndexEntry {
        private final Envelope envelope;
        private final String filename;

        private IndexEntry(Envelope envelope, String filename) {
            this.envelope = envelope;
            this.filename = filename;
        }

        private Envelope getEnvelope() {
            return envelope;
        }

        private String getFilename() {
            return filename;
        }
    }

    private class FirstEnvelopeFinder implements ItemVisitor {
        private final Coordinate coordinate;
        private String firstMatch = null;

        private FirstEnvelopeFinder(Coordinate c) {
            coordinate = c;
        }

        private String getFirstMatch() {
            return firstMatch;
        }

        @Override
        public void visitItem(Object item) {
            if (firstMatch == null) {
                IndexEntry ie = (IndexEntry) item;
                if (ie.getEnvelope().contains(coordinate)) {
                    firstMatch = ie.getFilename();
                }
            }
        }
    }
}
