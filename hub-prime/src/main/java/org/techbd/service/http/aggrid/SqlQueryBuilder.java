package org.techbd.service.http.aggrid;

import static com.google.common.collect.Streams.zip;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Sets;

/**
 * Builds SQL queries from ServerRowsRequest. TODO: switch to pure jOOQ, see
 * https://www.jooq.org/doc/latest/manual/sql-building/dynamic-sql/
 */
public class SqlQueryBuilder {

    private List<String> groupKeys;
    private List<String> rowGroups;
    private List<String> rowGroupsToInclude;
    private boolean isGrouping;
    private List<ColumnVO> valueColumns;
    private List<ColumnVO> pivotColumns;
    private Map<String, ColumnFilter> filterModel;
    private List<SortModel> sortModel;
    private int startRow, endRow;
    private List<ColumnVO> rowGroupCols;
    private Map<String, List<String>> pivotValues;
    private boolean isPivotMode;

    public String createSql(ServerRowsRequest request, String tableName, Map<String, List<String>> pivotValues) {
        this.valueColumns = request.getValueCols();
        this.pivotColumns = request.getPivotCols();
        this.groupKeys = request.getGroupKeys();
        this.rowGroupCols = request.getRowGroupCols();
        this.pivotValues = pivotValues;
        this.isPivotMode = request.isPivotMode();
        this.rowGroups = getRowGroups();
        this.rowGroupsToInclude = getRowGroupsToInclude();
        this.isGrouping = rowGroups.size() > groupKeys.size();
        this.filterModel = request.getFilterModel();
        this.sortModel = request.getSortModel();
        this.startRow = request.getStartRow();
        this.endRow = request.getEndRow();

        return selectSql() + fromSql(tableName) + whereSql() + groupBySql() + orderBySql() + limitSql();
    }

    private String selectSql() {
        List<String> selectCols;
        //rowGroupsToInclude.add("request_uri");
        boolean isPivoting = false;
        if (isPivotMode && !pivotColumns.isEmpty()) {
            if (null == rowGroupsToInclude || rowGroupsToInclude.isEmpty()) {
                selectCols = pivotColumns.stream().map(ColumnVO::getField).toList();
                isPivoting = true;
            } else {
                selectCols = concat(rowGroupsToInclude.stream(), extractPivotStatements()).collect(toList());
            }
        } else {
            Stream<String> valueCols = valueColumns.stream()
                    .map(valueCol -> valueCol.getAggFunc() + '(' + valueCol.getField() + ") as " + valueCol.getField());

            selectCols = concat(rowGroupsToInclude.stream(), valueCols).collect(toList());
        }

        return isGrouping || isPivoting ? "SELECT " + join(", ", selectCols) : "SELECT *";
    }

    private String fromSql(String tableName) {
        return format(" FROM %s", tableName);
    }

    private String whereSql() {
        String whereFilters = concat(getGroupColumns(), getFilters())
                .collect(joining(" AND "));

        if (whereFilters.contains(" AND ")) {
            whereFilters = whereFilters.substring(0, whereFilters.lastIndexOf(" AND "));
        }

        return whereFilters.isEmpty() ? "" : format(" WHERE %s", whereFilters);
    }

    private String groupBySql() {
        return isGrouping ? " GROUP BY " + join(", ", rowGroupsToInclude) : "";
    }

    private String orderBySql() {
        Function<SortModel, String> orderByMapper = model -> model.getColId() + " " + model.getSort();

        boolean isDoingGrouping = rowGroups.size() > groupKeys.size();
        int num = isDoingGrouping ? groupKeys.size() + 1 : MAX_VALUE;

        List<String> orderByCols = sortModel.stream()
                .filter(model -> !isDoingGrouping || rowGroups.contains(model.getColId()))
                .map(orderByMapper)
                .limit(num)
                .collect(toList());

        return orderByCols.isEmpty() ? "" : " ORDER BY " + join(",", orderByCols);
    }

    private String limitSql() {
        return " OFFSET " + startRow + " ROWS FETCH NEXT " + (endRow - startRow + 1) + " ROWS ONLY";
    }

    private Stream<String> getFilters() {
        Function<Map.Entry<String, ColumnFilter>, String> applyFilters = entry -> {
            String columnName = entry.getKey();
            ColumnFilter filter = entry.getValue();

            if (filter instanceof SetColumnFilter) {
                return setFilter().apply(columnName, (SetColumnFilter) filter);

            } else if (filter instanceof NumberColumnFilter) {
                return numberFilter().apply(columnName, (NumberColumnFilter) filter);
            } else if (filter instanceof TextColumnFilter) {
                return getFilterQueryForText((TextColumnFilter) filter, columnName);
            }

            return "";
        };

        return filterModel.entrySet().stream().map(applyFilters);
    }

    private String getFilterQueryForText(TextColumnFilter filter, String columnName) {
        if (filter.getType().equalsIgnoreCase("contains")) {
            return columnName + " like " + "'%" + filter.getFilter() + "%'";
        } else if (filter.getType().equalsIgnoreCase("notContains")) {
            return columnName + " not like " + "'%" + filter.getFilter() + "%'";
        } else if (filter.getType().equalsIgnoreCase("equals")) {
            return columnName + " = " + "'" + filter.getFilter() + "'";
        } else if (filter.getType().equalsIgnoreCase("notEqual")) {
            return columnName + " <> " + "'" + filter.getFilter() + "'";
        } else if (filter.getType().equalsIgnoreCase("startsWith")) {
            return columnName + " like " + "'" + filter.getFilter() + "%'";
        } else if (filter.getType().equalsIgnoreCase("endsWith")) {
            return columnName + " like " + "'%" + filter.getFilter() + "'";
        } else if (filter.getType().equalsIgnoreCase("blank")) {
            return columnName + " is null ";
        } else if (filter.getType().equalsIgnoreCase("notBlank")) {
            return columnName + " is not null ";
        } else {
            return "";
        }
    }

    private BiFunction<String, SetColumnFilter, String> setFilter() {
        return (String columnName, SetColumnFilter filter) -> columnName
                + (filter.getValues().isEmpty() ? " IN ('') " : " IN " + asString(filter.getValues()));
    }

    private BiFunction<String, NumberColumnFilter, String> numberFilter() {
        return (String columnName, NumberColumnFilter filter) -> {
            Integer filterValue = filter.getFilter();
            String filerType = filter.getType();
            String operator = operatorMap.get(filerType);

            return columnName
                    + (filerType.equals("inRange") ? " BETWEEN " + filterValue + " AND " + filter.getFilterTo()
                    : " " + operator + " " + filterValue);
        };
    }

    private Stream<String> extractPivotStatements() {

        // create pairs of pivot col and pivot value i.e. (DEALTYPE,Financial),
        // (BIDTYPE,Sell)...
        List<Set<Pair<String, String>>> pivotPairs = pivotValues.entrySet().stream()
                .map(e -> e.getValue().stream()
                .map(pivotValue -> Pair.of(e.getKey(), pivotValue))
                .collect(toCollection(LinkedHashSet::new)))
                .collect(toList());

        // create a cartesian product of decode statements for all pivot and value
        // columns combinations
        // i.e. sum(DECODE(DEALTYPE, 'Financial', DECODE(BIDTYPE, 'Sell',
        // CURRENTVALUE)))
        return Sets.cartesianProduct(pivotPairs)
                .stream()
                .flatMap(pairs -> {
                    String pivotColStr = pairs.stream()
                            .map(Pair::getRight)
                            .collect(joining("_"));

                    String decodeStr = pairs.stream()
                            .map(pair -> "DECODE(" + pair.getLeft() + ", '" + pair.getRight() + "'")
                            .collect(joining(", "));

                    String closingBrackets = IntStream
                            .range(0, pairs.size() + 1)
                            .mapToObj(i -> ")")
                            .collect(joining(""));

                    return valueColumns.stream()
                            .map(valueCol -> valueCol.getAggFunc() + "(" + decodeStr + ", " + valueCol.getField()
                            + closingBrackets + " \"" + pivotColStr + "_" + valueCol.getField() + "\"");
                });
    }

    private List<String> getRowGroupsToInclude() {
        return rowGroups.stream()
                .limit(groupKeys.size() + 1)
                .collect(toList());
    }

    private Stream<String> getGroupColumns() {
        return zip(groupKeys.stream(), rowGroups.stream(), (key, group) -> group + " = '" + key + "'");
    }

    private List<String> getRowGroups() {
        return rowGroupCols.stream()
                .map(ColumnVO::getField)
                .collect(toList());
    }

    private String asString(List<String> l) {
        return "(" + l.stream().map(s -> "\'" + s + "\'").collect(joining(", ")) + ")";
    }

    private Map<String, String> operatorMap = new HashMap<String, String>() {
        {
            put("equals", "=");
            put("notEqual", "<>");
            put("lessThan", "<");
            put("lessThanOrEqual", "<=");
            put("greaterThan", ">");
            put("greaterThanOrEqual", ">=");
        }
    };
}
