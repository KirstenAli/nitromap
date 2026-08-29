package dev.nitromap.query;

record TableRow(Table table, Object key, Object value) {

    Object read(String column) {
        return "_key".equalsIgnoreCase(column) ? key : table.read(value, column);
    }
}
