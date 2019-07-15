package de.komoot.batchdemreader;

import com.vividsolutions.jts.geom.Coordinate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.geotools.coverage.grid.GridCoverage2D;
import org.junit.Test;

import java.io.*;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Created by jan on 23.03.17.
 */
@Slf4j
public class URLReaderTest {

    @Test
    public void testConcurrency() throws URISyntaxException, IOException, InterruptedException {
        AtomicInteger errors = new AtomicInteger();
        File outFile = File.createTempFile("batchdemreader", ".tif.bz2");
        outFile.deleteOnExit();
        try (InputStream in = new BZip2CompressorInputStream(getClass().getResourceAsStream("N00E134.tif.bz2"))) {
            try (OutputStream out = new FileOutputStream(outFile)) {
                IOUtils.copy(in, out);
            }
        }

        ExecutorService e = Executors.newCachedThreadPool();
        for (int i = 0; i < 100; i++) {
            e.submit(() -> {
                try {
                    GridCoverage2D reader = URLReader.openReader(outFile);
                    for (int j = 0; j < 10000; j++) {
                        assertEquals(0.0, URLReader.extractValue(new Coordinate(134.1, 0.1), reader), 0.0001);
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                    log.error(ex.getMessage(), ex);
                }
            });
        }

        e.shutdown();
        log.info("Waiting for completion...");
        boolean success = e.awaitTermination(1, TimeUnit.MINUTES);
        if (errors.get() > 0 || success == false) {
            throw new RuntimeException("Not successfull");
        }
    }
}