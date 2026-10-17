package org.sqlcj.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads whether one embedded row of a result is absent.
 *
 * <p>A left-joined source contributes no row when the join finds no match, and
 * a driver reports that as every column of that source reading SQL
 * {@code NULL}. Generated code reads an embedded row from the consecutive
 * positions its wildcard selected, so the absence of the row is exactly the
 * whole of that range reading {@code NULL}, which is the one piece of
 * information this class reports and which needs no driver of its own.
 */
public final class SqlRow {

    private SqlRow() {
    }

    /**
     * Reports whether every column from the one-based {@code firstPosition} to
     * the one-based {@code lastPosition}, both inclusive, reads SQL
     * {@code NULL}. It reads only that range, and stops at the first column
     * that reads a value.
     */
    public static boolean isNull(
        ResultSet resultSet,
        int firstPosition,
        int lastPosition
    ) throws SQLException {
        for (int position = firstPosition; position <= lastPosition; position++) {
            if (resultSet.getObject(position) != null) {
                return false;
            }
        }

        return true;
    }
}
