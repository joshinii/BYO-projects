import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
  // ---- File format constants ----

  // The first 100 bytes of the file are the database header (only present on page 1).
  private static final int DATABASE_HEADER_SIZE = 100;
  private static final int PAGE_SIZE_OFFSET = 16;

  // B-tree page header fields, relative to the start of the page header.
  private static final int PAGE_TYPE_FIELD = 0;
  private static final int CELL_COUNT_FIELD = 3;
  private static final int RIGHTMOST_CHILD_FIELD = 8; // interior pages only

  // The cell pointer array (2 bytes per cell) starts right after the page header.
  private static final int LEAF_PAGE_HEADER_SIZE = 8;
  private static final int INTERIOR_PAGE_HEADER_SIZE = 12;

  private static final int TABLE_INTERIOR_PAGE_TYPE = 0x05;
  private static final int TABLE_LEAF_PAGE_TYPE = 0x0d;

  private static final int SCHEMA_PAGE_NUMBER = 1;
  private static final String SQLITE_INTERNAL_PREFIX = "sqlite_";

  // ---- Data types ----

  /** One row of the sqlite_schema table: type, name, tbl_name, rootpage, sql. */
  private record SchemaEntry(
      String objectType, String objectName, String tableName, int rootPageNumber, String createSql) {
    private boolean isTable() {
      return "table".equals(objectType);
    }

    private boolean isUserTable() {
      return isTable() && objectName != null && !objectName.startsWith(SQLITE_INTERNAL_PREFIX);
    }
  }

  /** A parsed `SELECT col[, col...] FROM table [WHERE col = value]` query. */
  private record SelectQuery(
      String tableName, String[] columnNames, String filterColumn, String filterValue) {}

  /**
   * Which record columns to output, the optional WHERE filter (-1 = no filter), and the
   * INTEGER PRIMARY KEY column whose value is the rowid (-1 = none).
   */
  private record RowSelection(
      int[] columnIndexes, int filterColumnIndex, String filterValue, int rowidAliasIndex) {}

  /** A cell's rowid and record: column serial types, plus a buffer at the first value. */
  private record Record(long rowid, long[] serialTypes, ByteBuffer body) {}

  @FunctionalInterface
  private interface IoAction {
    String run() throws IOException;
  }

  // ---- Command dispatch ----

  public static void main(String[] args) {
    if (args.length < 2) {
      System.out.println("Missing <database path> and <command>");
      return;
    }

    String databaseFilePath = args[0];
    String command = args[1];

    switch (command) {
      case ".dbinfo" -> printResultOrError(() -> readDbInfo(databaseFilePath));
      case ".tables" -> printResultOrError(() -> readTableNames(databaseFilePath));
      default -> runQuery(databaseFilePath, command);
    }
  }

  private static void printResultOrError(IoAction action) {
    try {
      System.out.println(action.run());
    } catch (IOException e) {
      System.out.println("Error reading file: " + e.getMessage());
    }
  }

  private static void runQuery(String databaseFilePath, String command) {
    String trimmedCommand = command.trim();
    String[] tokens = trimmedCommand.split("\\s+");

    if (trimmedCommand.toLowerCase(Locale.ROOT).startsWith("select count(*) from ")) {
      String tableName = removeSemicolons(tokens[tokens.length - 1]);
      printResultOrError(() -> String.valueOf(countTableRows(databaseFilePath, tableName)));
      return;
    }

    SelectQuery query = parseSelect(trimmedCommand);
    if (query == null) {
      System.out.println("Missing or invalid command passed: " + command);
      return;
    }
    printResultOrError(() -> readColumnValues(databaseFilePath, query));
  }

  // ---- SQL parsing ----

  // SELECT <columns> FROM <table> [WHERE <column> = <value>] [;]
  // The value is matched as a whole, so quoted strings may contain spaces.
  private static final Pattern SELECT_PATTERN = Pattern.compile(
      "^select\\s+(.+?)\\s+from\\s+(\\S+?);?(?:\\s+where\\s+(\\S+?)\\s*=\\s*(.+?))?\\s*;?\\s*$",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** Returns the parsed query, or null if the command isn't a supported SELECT. */
  private static SelectQuery parseSelect(String command) {
    Matcher matcher = SELECT_PATTERN.matcher(command);
    if (!matcher.matches()) {
      return null;
    }

    String[] columnNames = matcher.group(1).split(",");
    for (int i = 0; i < columnNames.length; i++) {
      columnNames[i] = columnNames[i].trim();
    }
    String tableName = removeSemicolons(matcher.group(2));
    String filterColumn = matcher.group(3);
    String filterValue = filterColumn == null ? null : unquoteSqlString(matcher.group(4));
    return new SelectQuery(tableName, columnNames, filterColumn, filterValue);
  }

  /** Finds the column's position in a CREATE TABLE statement's column list. */
  private static int findColumnIndex(String createSql, String columnName) throws IOException {
    int definitionsStart = createSql.indexOf('(');
    int definitionsEnd = createSql.lastIndexOf(')');
    if (definitionsStart < 0 || definitionsEnd <= definitionsStart) {
      throw new IOException("Invalid CREATE TABLE statement");
    }

    String[] definitions = createSql.substring(definitionsStart + 1, definitionsEnd).split(",");
    for (int index = 0; index < definitions.length; index++) {
      String declaredName = definitions[index].trim().split("\\s+", 2)[0];
      if (declaredName.equalsIgnoreCase(columnName)) {
        return index;
      }
    }
    throw new IOException("Column not found: " + columnName);
  }

  /**
   * Returns the index of the INTEGER PRIMARY KEY column, or -1 if there is none. That column
   * is an alias for the rowid: its value lives in the cell's rowid, not in the record.
   */
  private static int findRowidAliasIndex(String createSql) {
    int definitionsStart = createSql.indexOf('(');
    int definitionsEnd = createSql.lastIndexOf(')');
    if (definitionsStart < 0 || definitionsEnd <= definitionsStart) {
      return -1;
    }

    String[] definitions = createSql.substring(definitionsStart + 1, definitionsEnd).split(",");
    for (int index = 0; index < definitions.length; index++) {
      if (ROWID_ALIAS_PATTERN.matcher(definitions[index].trim()).matches()) {
        return index;
      }
    }
    return -1;
  }

  private static final Pattern ROWID_ALIAS_PATTERN = Pattern.compile(
      "^\\S+\\s+integer\\s+primary\\s+key\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static String removeSemicolons(String token) {
    return token.replace(";", "");
  }

  private static String unquoteSqlString(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  // ---- Query execution ----

  private static String readDbInfo(String databaseFilePath) throws IOException {
    try (FileInputStream databaseFile = new FileInputStream(databaseFilePath)) {
      int pageSize = readUnsignedShortAt(databaseFile, PAGE_SIZE_OFFSET);
      // Approximates the table count with the number of cells on the schema page.
      int tableCount = readUnsignedShortAt(databaseFile, DATABASE_HEADER_SIZE + CELL_COUNT_FIELD);

      // You can use print statements as follows for debugging, they'll be visible when running tests.
      System.err.println("Logs from your program will appear here!");

      return "database page size: " + pageSize + "\n" + "number of tables: " + tableCount;
    }
  }

  private static String readTableNames(String databaseFilePath) throws IOException {
    TreeSet<String> tableNames = new TreeSet<>();
    for (SchemaEntry entry : readSchema(databaseFilePath)) {
      if (entry.isUserTable()) {
        tableNames.add(entry.tableName());
      }
    }
    return String.join(" ", tableNames);
  }

  private static int countTableRows(String databaseFilePath, String tableName) throws IOException {
    int rootPageNumber = -1;
    for (SchemaEntry entry : readSchema(databaseFilePath)) {
      if (entry.isTable() && tableName.equals(entry.objectName())) {
        rootPageNumber = entry.rootPageNumber();
        break;
      }
    }
    if (rootPageNumber < 1) {
      throw new IOException("Table not found: " + tableName);
    }

    // Counts the cells on the root page only.
    ByteBuffer tablePage = ByteBuffer.wrap(readPage(databaseFilePath, rootPageNumber));
    return cellCount(tablePage, btreeHeaderOffset(rootPageNumber));
  }

  private static String readColumnValues(String databaseFilePath, SelectQuery query)
      throws IOException {
    SchemaEntry table = findTableEntry(databaseFilePath, query.tableName());

    int[] columnIndexes = new int[query.columnNames().length];
    for (int i = 0; i < columnIndexes.length; i++) {
      columnIndexes[i] = findColumnIndex(table.createSql(), query.columnNames()[i]);
    }
    int filterColumnIndex = query.filterColumn() == null
        ? -1
        : findColumnIndex(table.createSql(), query.filterColumn());
    RowSelection selection = new RowSelection(
        columnIndexes, filterColumnIndex, query.filterValue(), findRowidAliasIndex(table.createSql()));

    StringBuilder results = new StringBuilder();
    collectRows(databaseFilePath, table.rootPageNumber(), selection, results);
    return results.toString();
  }

  /** Walks the table b-tree rooted at pageNumber, appending matching rows in rowid order. */
  private static void collectRows(
      String databaseFilePath, int pageNumber, RowSelection selection, StringBuilder results)
      throws IOException {
    ByteBuffer page = ByteBuffer.wrap(readPage(databaseFilePath, pageNumber));
    int headerOffset = btreeHeaderOffset(pageNumber);
    int pageType = Byte.toUnsignedInt(page.get(headerOffset + PAGE_TYPE_FIELD));
    int cellCount = cellCount(page, headerOffset);

    if (pageType == TABLE_INTERIOR_PAGE_TYPE) {
      // Each interior cell starts with a 4-byte left child page number;
      // the page header stores the rightmost child.
      for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
        int cellOffset = cellOffset(page, headerOffset, INTERIOR_PAGE_HEADER_SIZE, cellIndex);
        int leftChildPage = page.getInt(cellOffset);
        collectRows(databaseFilePath, leftChildPage, selection, results);
      }
      int rightmostChildPage = page.getInt(headerOffset + RIGHTMOST_CHILD_FIELD);
      collectRows(databaseFilePath, rightmostChildPage, selection, results);
      return;
    }

    if (pageType != TABLE_LEAF_PAGE_TYPE) {
      throw new IOException("Unsupported table B-tree page type: " + pageType);
    }

    for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
      int cellOffset = cellOffset(page, headerOffset, LEAF_PAGE_HEADER_SIZE, cellIndex);
      String row = selectFromCell(page, cellOffset, selection);
      if (row != null) {
        if (results.length() > 0) {
          results.append('\n');
        }
        results.append(row);
      }
    }
  }

  /**
   * Returns the selected columns of one row joined by "|", or null if the row fails the
   * WHERE filter.
   */
  private static String selectFromCell(ByteBuffer page, int cellOffset, RowSelection selection)
      throws IOException {
    Record record = readRecordFromCell(page, cellOffset);
    int[] columnIndexes = selection.columnIndexes();
    String[] values = new String[columnIndexes.length];
    String actualFilterValue = null;

    // Column values are stored back to back, so every column must be read in order.
    for (int recordColumn = 0; recordColumn < record.serialTypes().length; recordColumn++) {
      long serialType = record.serialTypes()[recordColumn];
      String value = readRecordValue(record.body(), serialType);
      // The rowid alias column is stored as NULL; its real value is the rowid.
      if (recordColumn == selection.rowidAliasIndex() && serialType == 0) {
        value = Long.toString(record.rowid());
      }
      if (recordColumn == selection.filterColumnIndex()) {
        actualFilterValue = value;
      }
      for (int resultColumn = 0; resultColumn < columnIndexes.length; resultColumn++) {
        if (columnIndexes[resultColumn] == recordColumn) {
          values[resultColumn] = value;
        }
      }
    }

    if (selection.filterColumnIndex() >= 0 && !selection.filterValue().equals(actualFilterValue)) {
      return null;
    }
    for (String value : values) {
      if (value == null) {
        throw new IOException("Column index is outside the table record");
      }
    }
    return String.join("|", values);
  }

  // ---- Schema (sqlite_schema on page 1) ----

  private static List<SchemaEntry> readSchema(String databaseFilePath) throws IOException {
    ByteBuffer schemaPage = ByteBuffer.wrap(readPage(databaseFilePath, SCHEMA_PAGE_NUMBER));
    int headerOffset = btreeHeaderOffset(SCHEMA_PAGE_NUMBER);
    List<SchemaEntry> entries = new ArrayList<>();
    for (int cellIndex = 0; cellIndex < cellCount(schemaPage, headerOffset); cellIndex++) {
      int cellOffset = cellOffset(schemaPage, headerOffset, LEAF_PAGE_HEADER_SIZE, cellIndex);
      entries.add(readSchemaEntry(schemaPage, cellOffset));
    }
    return entries;
  }

  private static SchemaEntry findTableEntry(String databaseFilePath, String tableName)
      throws IOException {
    for (SchemaEntry entry : readSchema(databaseFilePath)) {
      if (entry.isTable() && tableName.equalsIgnoreCase(entry.objectName())) {
        return entry;
      }
    }
    throw new IOException("Table not found: " + tableName);
  }

  private static SchemaEntry readSchemaEntry(ByteBuffer schemaPage, int cellOffset)
      throws IOException {
    Record record = readRecordFromCell(schemaPage, cellOffset);
    long[] serialTypes = record.serialTypes();
    ByteBuffer body = record.body();

    String type = readTextColumn(body, serialTypes[0]);
    String name = readTextColumn(body, serialTypes[1]);
    String tableName = readTextColumn(body, serialTypes[2]);
    int rootPage = readIntegerColumn(body, serialTypes[3]);
    String createSql = readTextColumn(body, serialTypes[4]);
    return new SchemaEntry(type, name, tableName, rootPage, createSql);
  }

  // ---- Pages ----

  private static byte[] readPage(String databaseFilePath, int pageNumber) throws IOException {
    try (FileInputStream databaseFile = new FileInputStream(databaseFilePath)) {
      databaseFile.getChannel().position(PAGE_SIZE_OFFSET);
      byte[] pageSizeBytes = databaseFile.readNBytes(2);
      if (pageSizeBytes.length != 2) {
        throw new IOException("Database file header is incomplete");
      }

      int pageSize = Short.toUnsignedInt(ByteBuffer.wrap(pageSizeBytes).getShort());
      // SQLite uses 1 in the header to represent a 65536-byte page.
      if (pageSize == 1) {
        pageSize = 65536;
      }

      // Pages are numbered from 1.
      databaseFile.getChannel().position((long) (pageNumber - 1) * pageSize);
      byte[] page = databaseFile.readNBytes(pageSize);
      if (page.length != pageSize) {
        throw new IOException("Database page is incomplete");
      }
      return page;
    }
  }

  private static int readUnsignedShortAt(FileInputStream file, long offset) throws IOException {
    file.getChannel().position(offset);
    byte[] bytes = new byte[2];
    file.read(bytes);
    return Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort());
  }

  /** Page 1 starts with the database header; other pages start directly with the b-tree header. */
  private static int btreeHeaderOffset(int pageNumber) {
    return pageNumber == 1 ? DATABASE_HEADER_SIZE : 0;
  }

  private static int cellCount(ByteBuffer page, int headerOffset) {
    return Short.toUnsignedInt(page.getShort(headerOffset + CELL_COUNT_FIELD));
  }

  /** Reads the cell's location from the cell pointer array that follows the page header. */
  private static int cellOffset(ByteBuffer page, int headerOffset, int headerSize, int cellIndex) {
    int pointerOffset = headerOffset + headerSize + cellIndex * 2;
    return Short.toUnsignedInt(page.getShort(pointerOffset));
  }

  // ---- Record format ----

  /**
   * Decodes a table leaf cell: [payload size varint][rowid varint][payload]. The payload is a
   * record: [header size varint][one serial type varint per column][column values].
   */
  private static Record readRecordFromCell(ByteBuffer page, int cellOffset) {
    page.position(cellOffset);
    int payloadSize = Math.toIntExact(readVarint(page));
    long rowid = readVarint(page);

    byte[] payload = new byte[payloadSize];
    page.get(payload);
    ByteBuffer body = ByteBuffer.wrap(payload);

    // The header size includes its own varint, so it marks where the values begin.
    int headerSize = Math.toIntExact(readVarint(body));
    long[] serialTypes = new long[headerSize];
    int columnCount = 0;
    while (body.position() < headerSize) {
      serialTypes[columnCount++] = readVarint(body);
    }
    return new Record(rowid, Arrays.copyOf(serialTypes, columnCount), body);
  }

  /** Reads one column value of any type as a string, advancing past it. */
  private static String readRecordValue(ByteBuffer record, long serialType) throws IOException {
    if (serialType == 0) { // NULL
      return "";
    }
    if (serialType >= 1 && serialType <= 6) {
      return Long.toString(readSignedInteger(record, serialTypeLength(serialType)));
    }
    if (serialType == 7) {
      return Double.toString(record.getDouble());
    }
    if (serialType == 8) {
      return "0";
    }
    if (serialType == 9) {
      return "1";
    }
    if (isTextSerialType(serialType)) {
      return readTextColumn(record, serialType);
    }

    // BLOBs are skipped.
    record.position(record.position() + serialTypeLength(serialType));
    return "";
  }

  /** Reads a big-endian two's-complement integer of the given byte width. */
  private static long readSignedInteger(ByteBuffer record, int valueSize) {
    int firstByte = Byte.toUnsignedInt(record.get(record.position()));
    long integerValue = 0;
    for (int byteIndex = 0; byteIndex < valueSize; byteIndex++) {
      integerValue = (integerValue << 8) | Byte.toUnsignedInt(record.get());
    }
    // Sign-extend if the most significant bit is set.
    if ((firstByte & 0x80) != 0) {
      integerValue |= -1L << (valueSize * 8);
    }
    return integerValue;
  }

  /** Reads a non-negative integer column (used for rootpage). */
  private static int readIntegerColumn(ByteBuffer record, long serialType) throws IOException {
    if (serialType == 8) {
      return 0;
    }
    if (serialType == 9) {
      return 1;
    }

    int valueSize = serialTypeLength(serialType);
    int integerValue = 0;
    for (int byteIndex = 0; byteIndex < valueSize; byteIndex++) {
      integerValue = (integerValue << 8) | Byte.toUnsignedInt(record.get());
    }
    return integerValue;
  }

  /** Reads a TEXT column, or skips the value and returns null if it isn't TEXT. */
  private static String readTextColumn(ByteBuffer record, long serialType) throws IOException {
    int valueSize = serialTypeLength(serialType);
    if (isTextSerialType(serialType)) {
      byte[] value = new byte[valueSize];
      record.get(value);
      return new String(value, StandardCharsets.UTF_8);
    }

    record.position(record.position() + valueSize);
    return null;
  }

  /** Odd serial types >= 13 are TEXT; even ones >= 12 are BLOB. */
  private static boolean isTextSerialType(long serialType) {
    return serialType >= 13 && (serialType & 1) == 1;
  }

  /** Number of bytes a value of this serial type occupies in the record body. */
  private static int serialTypeLength(long serialType) throws IOException {
    if (serialType == 0 || serialType == 8 || serialType == 9) {
      return 0; // NULL and the constants 0/1 store no bytes
    }
    if (serialType >= 1 && serialType <= 4) {
      return (int) serialType; // 1-, 2-, 3-, 4-byte integers
    }
    if (serialType == 5) {
      return 6;
    }
    if (serialType == 6 || serialType == 7) {
      return 8; // 8-byte integer or float
    }
    if (serialType >= 12) {
      long base = serialType % 2 == 0 ? 12 : 13; // BLOB : TEXT
      return Math.toIntExact((serialType - base) / 2);
    }
    throw new IOException("Invalid SQLite serial type: " + serialType);
  }

  /**
   * Decodes a big-endian varint: up to 8 bytes contribute 7 bits each (high bit set = more
   * bytes follow); a 9th byte contributes all 8 bits.
   */
  private static long readVarint(ByteBuffer buffer) {
    long decodedValue = 0;
    for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
      int currentByte = Byte.toUnsignedInt(buffer.get());
      decodedValue = (decodedValue << 7) | (currentByte & 0x7f);
      if ((currentByte & 0x80) == 0) {
        return decodedValue;
      }
    }
    return (decodedValue << 8) | Byte.toUnsignedInt(buffer.get());
  }
}
