package de.komoot.batchdemreader;

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

import javax.media.jai.Interpolation;
import java.awt.geom.Point2D;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * DEM file reader where all files are located in a (remote http) folder.
 *
 * @author jan
 */
public class URLReader implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(URLReader.class);
	private static final double TOLERANCE = 0.000001;
	private final URL demSourceBase;
	private final File demDirectory;
	private final Map<String, GridCoverage2D> openReaders = new HashMap<>();
	private STRtree index = new STRtree();

	public URLReader(URL demSourceBase) throws IOException {
		this.demSourceBase = demSourceBase;
		this.demDirectory = new File(new File(System.getProperty("java.io.tmpdir")), "dem");
		demDirectory.mkdir();
		try(InputStream stream = new GZIPInputStream(new URL(demSourceBase, "index.list.gz").openStream())) {
			populateIndex(stream);
		}
	}

	/**
	 * Reads the value at the given coordinate.
	 * @param c a coordinate
	 * @return the elevation value or {@link Double#NaN} if no data is available
	 */
	public double getValueAt(Coordinate c) {
		GridCoverage2D coverage = findCoverage(c);
		if(coverage == null) {
			return Double.NaN;
		} else {
			Point2D.Double p = new Point2D.Double(c.x, c.y);
			double[] r = new double[1];
			try {
				return coverage.evaluate(p, r)[0];
			} catch(PointOutsideCoverageException e) {
				throw new RuntimeException(String.format(Locale.US, "For %s (%s) (%f,%f): %s", coverage.getName(), coverage.getEnvelope(), c.x, c.y, e.getMessage()), e);
			}
		}
	}

	private GridCoverage2D findCoverage(Coordinate c) {
		FirstEnvelopeFinder fmf = new FirstEnvelopeFinder(c);
		index.query(new Envelope(c), fmf);

		String filename = fmf.getFirstMatch();
		if(filename != null) {
			GridCoverage2D coverage = openReaders.get(filename);
			if(coverage == null) {
				String extractedFilename = filename.replace(".bz2", "");
				File temp = new File(demDirectory, extractedFilename);
				if(temp.exists() == false) {
					try(InputStream in = new URL(demSourceBase, filename).openStream();
						OutputStream out = new FileOutputStream(temp)) {
						IOUtils.copy(new BZip2CompressorInputStream(in), out);
					} catch(MalformedURLException e) {
						throw new RuntimeException("Could not open source url" + filename, e);
					} catch(FileNotFoundException e) {
						throw new RuntimeException("Could not open temp file " + temp, e);
					} catch(IOException e) {
						throw new RuntimeException("Could not open or write " + filename, e);
					}
				}
				//open previously extracted file

				try {
					logger.info("Opening reader {}", temp);
					coverage = openReader(temp);
					openReaders.put(filename, coverage);
				} catch(IOException e) {
					throw new RuntimeException("Could not open file " + temp, e);
				}
			}
			return coverage;
		} else {
			return null;
		}
	}

	private void populateIndex(InputStream stream) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
		String line;
		while((line = reader.readLine()) != null) {
			if(line.startsWith("#") == false) {
				String[] parts = line.split(",");
				if(parts.length >= 5) {
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

	private static GridCoverage2D openReader(File f) throws IOException {
		GeoTiffReader reader = new GeoTiffReader(f);

		GridCoverage2D c = reader.read(null);
		GridCoverage2D coverage = Interpolator2D.create(c, Interpolation.getInstance(Interpolation.INTERP_BILINEAR));

		int dims = coverage.getNumSampleDimensions();
		if(dims != 1) {
			throw new IOException("Given coverage has " + dims + " dimensions but elevation data should have only one!");
		}
		return coverage;
	}

	static class IndexEntry {
		private final Envelope envelope;
		private final String filename;

		IndexEntry(Envelope envelope, String filename) {
			this.envelope = envelope;
			this.filename = filename;
		}

		public Envelope getEnvelope() {
			return envelope;
		}

		public String getFilename() {
			return filename;
		}
	}

	class FirstEnvelopeFinder implements ItemVisitor {
		private final Coordinate coordinate;
		private String firstMatch = null;

		public FirstEnvelopeFinder(Coordinate c) {
			coordinate = c;
		}

		public String getFirstMatch() {
			return firstMatch;
		}

		@Override
		public void visitItem(Object item) {
			if(firstMatch == null) {
				IndexEntry ie = (IndexEntry) item;
				if(ie.getEnvelope().contains(coordinate)) {
					firstMatch = ie.getFilename();
				}
			}
		}
	}
}
