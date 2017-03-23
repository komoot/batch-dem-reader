package de.komoot.batchdemreader;

import com.vividsolutions.jts.geom.Coordinate;

import javax.annotation.Nonnull;

/**
 * Digital elevation mode reader
 *
 * Created by jan on 23.03.17.
 */
public interface DemReader extends AutoCloseable {

	/**
	 * Reads the elevation value at the given coordinate
	 * @param c coordinate
	 * @return the value at that position or {@link Double#NaN} if no value is available for that coordinate.
	 */
	double getValueAt(@Nonnull Coordinate c);

	@Override
	void close() throws Exception;
}
