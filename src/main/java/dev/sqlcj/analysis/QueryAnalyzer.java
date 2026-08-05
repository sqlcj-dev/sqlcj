package dev.sqlcj.analysis;

import dev.sqlcj.parser.Query;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.List;

public final class QueryAnalyzer {

    public QueryModel analyze(Query query, Statement statement) {
        if (statement instanceof Select select) {
            return analyzeSelect(query, select);
        }

        if (statement instanceof Insert) {
            throw new UnsupportedOperationException("INSERT is not supported yet");
        }

        if (statement instanceof Update) {
            throw new UnsupportedOperationException("UPDATE is not supported yet");
        }

        if (statement instanceof Delete) {
            throw new UnsupportedOperationException("DELETE is not supported yet");
        }

        throw new UnsupportedOperationException(
                statement.getClass().getSimpleName()
        );
    }

    private QueryModel analyzeSelect(Query query, Select select) {
        PlainSelect plainSelect = select.getPlainSelect();
        Table table = getTable(plainSelect);

        List<Integer> parameters = extractParameters(plainSelect);

        return new QueryModel(
                query.name(),
                query.type(),
                table.getName(),
                parameters
        );
    }

    private Table getTable(PlainSelect plainSelect) {
        if (!(plainSelect.getFromItem() instanceof Table table)) {
            throw new UnsupportedOperationException("Only table sources are supported.");
        }

        return table;
    }

    private List<Integer> extractParameters(PlainSelect plainSelect) {
        List<Integer> parameters = new ArrayList<>();

        if (plainSelect.getWhere() == null) {
            return parameters;
        }

        plainSelect.getWhere().accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(JdbcParameter parameter, S context) {
                parameters.add(parameter.getIndex());
                return null;
            }
        });

        return parameters;
    }
}
