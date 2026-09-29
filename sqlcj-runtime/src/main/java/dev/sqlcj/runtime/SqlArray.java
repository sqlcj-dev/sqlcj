package dev.sqlcj.runtime;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A list argument bound as a SQL array of one element type.
 *
 * <p>{@link JdbcQueryExecutor} binds {@link #elements()} as an array of
 * {@link #elementType()}, which is the database's own name of the element type,
 * such as {@code int4} or the declared name of an enum type. That name is the
 * one piece of information JDBC needs to build an array value, so the runtime
 * needs no driver of its own.
 *
 * <p>{@code elements} may be {@code null}, which is bound as a SQL {@code NULL}
 * array, and it may hold {@code null} elements, which are bound as
 * {@code NULL}s of the element type. The elements themselves are never
 * inspected or reordered.
 *
 * <p>{@link #getList} reads an array column back the other way: a SQL
 * {@code NULL} is read as {@code null} and every other value as a list holding
 * one element per array element, in array order.
 */
public record SqlArray(
    String elementType,
    List<?> elements
) {

    /**
     * An array whose elements are converted one by one, such as the labels of a
     * list of generated enum constants. A {@code null} list stays {@code null},
     * a {@code null} element stays {@code null}, and every other element is the
     * value {@code converter} returns for it.
     */
    public static <T> SqlArray of(
        String elementType,
        List<T> elements,
        Function<? super T, ?> converter
    ) {
        if (elements == null) {
            return new SqlArray(elementType, null);
        }

        List<Object> converted = new ArrayList<>(elements.size());

        for (T element : elements) {
            converted.add(
                element == null
                    ? null
                    : converter.apply(element)
            );
        }

        return new SqlArray(elementType, converted);
    }

    /**
     * Reads the array column at the one-based {@code position} as a list of
     * {@code elementType} values, in array order, or {@code null} when the
     * column is SQL {@code NULL}. A {@code NULL} element is read as
     * {@code null}.
     */
    public static <T> List<T> getList(
        ResultSet resultSet,
        int position,
        Class<T> elementType
    ) throws SQLException {
        return getList(resultSet, position, elementType, element -> element);
    }

    /**
     * Reads the array column at the one-based {@code position} the same way and
     * converts each element that is not {@code null} with {@code converter},
     * such as a label back into a generated enum constant.
     */
    public static <E, T> List<T> getList(
        ResultSet resultSet,
        int position,
        Class<E> elementType,
        Function<? super E, ? extends T> converter
    ) throws SQLException {
        Array array = resultSet.getArray(position);

        if (array == null) {
            return null;
        }

        try {
            List<T> elements = new ArrayList<>();

            try (ResultSet rows = array.getResultSet()) {
                while (rows.next()) {
                    E element = rows.getObject(2, elementType);

                    elements.add(
                        element == null
                            ? null
                            : converter.apply(element)
                    );
                }
            }

            return elements;
        } finally {
            array.free();
        }
    }
}
