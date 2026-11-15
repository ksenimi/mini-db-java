import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;

public final class MiniDb {
    private MiniDb() { }

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, "UTF-8"));
        System.setErr(new PrintStream(System.err, true, "UTF-8"));
        Path file = Paths.get(args.length == 0 ? "data/database.bin" : args[0]);
        Database db;
        try { db = new Database(file); }
        catch (IOException | IllegalArgumentException ex) {
            System.err.println("Не удалось открыть базу данных: " + ex.getMessage());
            return;
        }
        System.out.println("MiniDb готова к работе (для выхода введите EXIT)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder pending = new StringBuilder();
        while (true) {
            System.out.print(pending.length() == 0 ? "запрос> " : "...> ");
            String line = in.readLine();
            if (line == null) break;
            if (pending.length() == 0 && (line.trim().equalsIgnoreCase("exit") || line.trim().equalsIgnoreCase("quit"))) break;
            pending.append(line).append('\n');
            List<String> statements = splitStatements(pending.toString());
            if (statements.isEmpty()) continue;
            for (String sql : statements) {
                try { System.out.println(db.execute(sql)); }
                catch (IllegalArgumentException | IOException ex) { System.out.println("Ошибка: " + ex.getMessage()); }
            }
            pending.delete(0, lastStatementEnd(pending.toString()));
            if (pending.toString().trim().isEmpty()) pending.setLength(0);
        }
    }

    static List<String> splitStatements(String input) {
        List<String> out = new ArrayList<>();
        boolean quoted = false;
        int begin = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < input.length() && input.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (c == ';' && !quoted) {
                String s = input.substring(begin, i).trim();
                if (!s.isEmpty()) out.add(s);
                begin = i + 1;
            }
        }
        return out;
    }

    private static int lastStatementEnd(String input) {
        boolean quoted = false;
        int end = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\'') {
                if (quoted && i + 1 < input.length() && input.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (input.charAt(i) == ';' && !quoted) end = i + 1;
        }
        return end;
    }

    enum Kind { INT, DOUBLE, STRING, DATE, BOOLEAN }

    static final class Column implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final Kind kind;
        Column(String name, Kind kind) { this.name = name; this.kind = kind; }
    }

    static final class ForeignKey implements Serializable {
        private static final long serialVersionUID = 1L;
        final String column, targetTable, targetColumn;
        ForeignKey(String column, String targetTable, String targetColumn) {
            this.column = column; this.targetTable = targetTable; this.targetColumn = targetColumn;
        }
    }

    static final class Table implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name, primaryKey;
        final LinkedHashMap<String, Column> columns = new LinkedHashMap<>();
        final List<ForeignKey> foreignKeys = new ArrayList<>();
        final LinkedHashMap<Object, LinkedHashMap<String, Object>> rows = new LinkedHashMap<>();
        Table(String name, String primaryKey, Column... cols) {
            this.name = name; this.primaryKey = primaryKey;
            for (Column c : cols) columns.put(c.name, c);
            if (!columns.containsKey(primaryKey)) throw new IllegalArgumentException("Неизвестный первичный ключ");
        }
        Column column(String name) {
            Column c = columns.get(name);
            if (c == null) throw new IllegalArgumentException("Неизвестный столбец " + name + " в таблице " + this.name);
            return c;
        }
    }

    static final class Index implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name, table, column;
        final HashMap<Object, LinkedHashSet<Object>> keys = new HashMap<>();
        Index(String name, String table, String column) { this.name = name; this.table = table; this.column = column; }
        void rebuild(Table t) {
            keys.clear();
            for (Map.Entry<Object, LinkedHashMap<String, Object>> e : t.rows.entrySet())
                keys.computeIfAbsent(e.getValue().get(column), k -> new LinkedHashSet<>()).add(e.getKey());
        }
    }

    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final LinkedHashMap<String, Table> tables = new LinkedHashMap<>();
        final LinkedHashMap<String, Index> indexes = new LinkedHashMap<>();
    }

    public static final class Database {
        private final Path file;
        private State state;
        public Database(Path file) throws IOException {
            this.file = file;
            if (Files.exists(file)) {
                try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
                    Object value = in.readObject();
                    if (!(value instanceof State)) throw new IOException("Неверный формат файла базы данных");
                    state = (State) value;
                    validate(state);
                } catch (ClassNotFoundException ex) { throw new IOException("Неподдерживаемый формат файла базы данных", ex); }
            } else {
                state = new State();
                Table makers = new Table("manufacturers", "brand",
                    new Column("brand", Kind.STRING), new Column("country", Kind.STRING), new Column("founded", Kind.DATE));
                Table cars = new Table("cars", "id", new Column("id", Kind.INT), new Column("brand", Kind.STRING),
                    new Column("price", Kind.DOUBLE), new Column("available", Kind.BOOLEAN));
                cars.foreignKeys.add(new ForeignKey("brand", "manufacturers", "brand"));
                state.tables.put(makers.name, makers);
                state.tables.put(cars.name, cars);
            }
        }

        public synchronized String execute(String sql) throws IOException {
            String s = sql.trim();
            if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
            if (s.isEmpty()) throw new IllegalArgumentException("Пустой запрос");
            if (starts(s, "SELECT")) return select(s);
            State next = copy(state);
            String result;
            if (starts(s, "INSERT INTO")) result = insert(next, s);
            else if (starts(s, "UPDATE")) result = update(next, s);
            else if (starts(s, "DELETE FROM")) result = delete(next, s);
            else if (starts(s, "CREATE INDEX")) result = createIndex(next, s);
            else if (starts(s, "CREATE TABLE")) result = createTable(next, s);
            else if (starts(s, "DROP TABLE")) result = dropTable(next, s);
            else throw new IllegalArgumentException("Неподдерживаемый запрос");
            validate(next);
            persist(next);
            state = next;
            return result;
        }

        private void persist(State next) throws IOException {
            Path absolute = file.toAbsolutePath();
            Path parent = absolute.getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "minidb-", ".tmp");
            try {
                try (FileOutputStream raw = new FileOutputStream(temp.toFile());
                     ObjectOutputStream out = new ObjectOutputStream(raw)) {
                    out.writeObject(next);
                    out.flush();
                    raw.getFD().sync();
                }
                try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        }

        private static State copy(State original) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(original); }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (State) in.readObject();
            } catch (ClassNotFoundException ex) { throw new IOException(ex); }
        }

        private static void validate(State s) {
            for (Table t : s.tables.values()) {
                for (Map.Entry<Object, LinkedHashMap<String, Object>> e : t.rows.entrySet()) {
                    if (e.getKey() == null || !e.getKey().equals(e.getValue().get(t.primaryKey)))
                        throw new IllegalArgumentException("Неверный первичный ключ в таблице " + t.name);
                    for (ForeignKey fk : t.foreignKeys) {
                        Table target = table(s, fk.targetTable);
                        if (!target.primaryKey.equals(fk.targetColumn)) throw new IllegalArgumentException("Внешний ключ должен ссылаться на первичный ключ");
                        Object value = e.getValue().get(fk.column);
                        if (value != null && !target.rows.containsKey(value))
                            throw new IllegalArgumentException("Нарушение внешнего ключа: " + t.name + "." + fk.column + " = " + value);
                    }
                }
            }
            for (Index idx : s.indexes.values()) {
                Table t = table(s, idx.table);
                t.column(idx.column);
                Index expected = new Index(idx.name, idx.table, idx.column);
                expected.rebuild(t);
                if (!expected.keys.equals(idx.keys)) throw new IllegalArgumentException("Повреждён индекс " + idx.name);
            }
        }

        private static String insert(State s, String sql) {
            Matcher m = match("(?is)^INSERT\\s+INTO\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?\\s+VALUES\\s+(.+)$", sql);
            Table t = table(s, m.group(1));
            List<String> names = m.group(2) == null ? new ArrayList<>(t.columns.keySet()) : identifiers(m.group(2));
            if (names.isEmpty() || new HashSet<>(names).size() != names.size()) throw new IllegalArgumentException("Список столбцов пуст или содержит повторы");
            for (String name : names) t.column(name);
            List<String> tuples = splitTop(m.group(3), ',');
            int count = 0;
            for (String tuple : tuples) {
                String v = tuple.trim();
                if (!v.startsWith("(") || !v.endsWith(")")) throw new IllegalArgumentException("Значения строки должны быть заключены в скобки");
                List<String> values = splitTop(v.substring(1, v.length() - 1), ',');
                if (values.size() != names.size()) throw new IllegalArgumentException("Количество столбцов и значений не совпадает");
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (String col : t.columns.keySet()) row.put(col, null);
                for (int i = 0; i < names.size(); i++) row.put(names.get(i), parse(values.get(i), t.column(names.get(i)).kind));
                Object key = row.get(t.primaryKey);
                if (key == null) throw new IllegalArgumentException("Первичный ключ не может быть NULL");
                if (t.rows.putIfAbsent(key, row) != null) throw new IllegalArgumentException("Повтор первичного ключа: " + key);
                count++;
            }
            rebuild(s, t.name);
            return "Вставлено строк: " + count;
        }

        private static String update(State s, String sql) {
            Matcher m = match("(?is)^UPDATE\\s+(\\w+)\\s+SET\\s+(.+)$", sql);
            Table t = table(s, m.group(1));
            String assignments = m.group(2);
            int wherePosition = keywordOutsideQuotes(assignments, "WHERE");
            String whereText = null;
            if (wherePosition >= 0) {
                whereText = assignments.substring(wherePosition + 5).trim();
                assignments = assignments.substring(0, wherePosition).trim();
            }
            LinkedHashMap<String, Object> changes = new LinkedHashMap<>();
            for (String assignment : splitTop(assignments, ',')) {
                Matcher a = match("(?is)^(\\w+)\\s*=\\s*(.+)$", assignment.trim());
                String col = a.group(1).toLowerCase(Locale.ROOT);
                if (changes.containsKey(col)) throw new IllegalArgumentException("Повторное присваивание столбца: " + col);
                changes.put(col, parse(a.group(2), t.column(col).kind));
            }
            Condition where = condition(whereText, t);
            int count = 0;
            LinkedHashMap<Object, LinkedHashMap<String, Object>> updated = new LinkedHashMap<>();
            for (LinkedHashMap<String, Object> row : t.rows.values()) {
                if (where == null || where.test(row)) { row.putAll(changes); count++; }
                Object key = row.get(t.primaryKey);
                if (key == null || updated.putIfAbsent(key, row) != null)
                    throw new IllegalArgumentException("После UPDATE первичный ключ равен NULL или повторяется: " + key);
            }
            t.rows.clear(); t.rows.putAll(updated);
            rebuild(s, t.name);
            return "Обновлено строк: " + count;
        }

        private static String delete(State s, String sql) {
            Matcher m = match("(?is)^DELETE\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$", sql);
            Table t = table(s, m.group(1));
            Condition where = condition(m.group(2), t);
            int before = t.rows.size();
            t.rows.values().removeIf(row -> where == null || where.test(row));
            rebuild(s, t.name);
            return "Удалено строк: " + (before - t.rows.size());
        }

        private String select(String sql) {
            Matcher m = match("(?is)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.+))?$", sql);
            Table t = table(state, m.group(2));
            List<String> cols = m.group(1).trim().equals("*") ? new ArrayList<>(t.columns.keySet()) : identifiers(m.group(1));
            if (cols.isEmpty()) throw new IllegalArgumentException("Не указаны столбцы для выборки");
            for (String col : cols) t.column(col);
            Condition where = condition(m.group(3), t);
            Collection<LinkedHashMap<String, Object>> candidates = t.rows.values();
            if (where != null && where.op.equals("=")) {
                if (where.column.equals(t.primaryKey)) {
                    LinkedHashMap<String, Object> row = t.rows.get(where.value);
                    candidates = row == null ? Collections.emptyList() : Collections.singletonList(row);
                } else {
                    for (Index idx : state.indexes.values()) if (idx.table.equals(t.name) && idx.column.equals(where.column)) {
                        LinkedHashSet<Object> keys = idx.keys.get(where.value);
                        List<LinkedHashMap<String, Object>> found = new ArrayList<>();
                        if (keys != null) for (Object key : keys) found.add(t.rows.get(key));
                        candidates = found;
                        break;
                    }
                }
            }
            List<List<String>> output = new ArrayList<>();
            for (LinkedHashMap<String, Object> row : candidates) if (where == null || where.test(row)) {
                List<String> values = new ArrayList<>();
                for (String col : cols) values.add(row.get(col) == null ? "NULL" : row.get(col).toString());
                output.add(values);
            }
            return format(cols, output);
        }

        private static String createIndex(State s, String sql) {
            Matcher m = match("(?is)^CREATE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)$", sql);
            String name = m.group(1).toLowerCase(Locale.ROOT), table = m.group(2).toLowerCase(Locale.ROOT), col = m.group(3).toLowerCase(Locale.ROOT);
            if (s.indexes.containsKey(name)) throw new IllegalArgumentException("Индекс уже существует: " + name);
            Table t = table(s, table); t.column(col);
            Index idx = new Index(name, table, col); idx.rebuild(t); s.indexes.put(name, idx);
            return "Создан индекс: " + name;
        }

        private static String createTable(State s, String sql) {
            Matcher m = match("(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)$", sql);
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (s.tables.containsKey(name)) throw new IllegalArgumentException("Таблица уже существует: " + name);
            LinkedHashMap<String, Column> cols = new LinkedHashMap<>();
            List<ForeignKey> fks = new ArrayList<>();
            String pk = null;
            for (String part : splitTop(m.group(2), ',')) {
                String p = part.trim();
                Matcher fk = Pattern.compile("(?is)^FOREIGN\\s+KEY\\s*\\(\\s*(\\w+)\\s*\\)\\s+REFERENCES\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)$").matcher(p);
                Matcher primary = Pattern.compile("(?is)^PRIMARY\\s+KEY\\s*\\(\\s*(\\w+)\\s*\\)$").matcher(p);
                if (fk.matches()) fks.add(new ForeignKey(fk.group(1).toLowerCase(Locale.ROOT), fk.group(2).toLowerCase(Locale.ROOT), fk.group(3).toLowerCase(Locale.ROOT)));
                else if (primary.matches()) { if (pk != null) throw new IllegalArgumentException("Указано несколько первичных ключей"); pk = primary.group(1).toLowerCase(Locale.ROOT); }
                else {
                    Matcher c = match("(?is)^(\\w+)\\s+(INT|INTEGER|DOUBLE|FLOAT|STRING|TEXT|DATE|BOOLEAN|BOOL)(\\s+PRIMARY\\s+KEY)?$", p);
                    String col = c.group(1).toLowerCase(Locale.ROOT);
                    if (cols.putIfAbsent(col, new Column(col, kind(c.group(2)))) != null) throw new IllegalArgumentException("Повтор столбца: " + col);
                    if (c.group(3) != null) { if (pk != null) throw new IllegalArgumentException("Указано несколько первичных ключей"); pk = col; }
                }
            }
            if (pk == null) throw new IllegalArgumentException("Необходимо указать PRIMARY KEY");
            Table t = new Table(name, pk, cols.values().toArray(new Column[0]));
            for (ForeignKey fk : fks) {
                Column source = t.column(fk.column);
                Table target = table(s, fk.targetTable);
                if (!target.primaryKey.equals(fk.targetColumn) || source.kind != target.column(fk.targetColumn).kind)
                    throw new IllegalArgumentException("Тип внешнего ключа должен совпадать с типом первичного ключа связанной таблицы");
                t.foreignKeys.add(fk);
            }
            s.tables.put(name, t);
            return "Создана таблица: " + name;
        }

        private static String dropTable(State s, String sql) {
            Matcher m = match("(?is)^DROP\\s+TABLE\\s+(\\w+)$", sql);
            String name = m.group(1).toLowerCase(Locale.ROOT);
            table(s, name);
            for (Table t : s.tables.values()) for (ForeignKey fk : t.foreignKeys)
                if (!t.name.equals(name) && fk.targetTable.equals(name)) throw new IllegalArgumentException("На таблицу ссылается таблица " + t.name);
            s.tables.remove(name);
            s.indexes.values().removeIf(idx -> idx.table.equals(name));
            return "Удалена таблица: " + name;
        }

        private static void rebuild(State s, String table) {
            for (Index idx : s.indexes.values()) if (idx.table.equals(table)) idx.rebuild(s.tables.get(table));
        }
        private static Table table(State s, String name) {
            Table t = s.tables.get(name.toLowerCase(Locale.ROOT));
            if (t == null) throw new IllegalArgumentException("Неизвестная таблица: " + name);
            return t;
        }
    }

    static final class Condition {
        final String column, op;
        final Object value;
        Condition(String column, String op, Object value) { this.column = column; this.op = op; this.value = value; }
        @SuppressWarnings({"rawtypes", "unchecked"})
        boolean test(Map<String, Object> row) {
            Object left = row.get(column);
            if (op.equals("=")) return Objects.equals(left, value);
            if (op.equals("!=") || op.equals("<>")) return !Objects.equals(left, value);
            if (left == null || value == null) return false;
            int c = ((Comparable) left).compareTo(value);
            switch (op) {
                case ">": return c > 0;
                case ">=": return c >= 0;
                case "<": return c < 0;
                case "<=": return c <= 0;
                default: throw new IllegalArgumentException("Неверный оператор сравнения");
            }
        }
    }

    private static Condition condition(String input, Table t) {
        if (input == null) return null;
        Matcher m = match("(?is)^(\\w+)\\s*(<=|>=|<>|!=|=|<|>)\\s*(.+)$", input.trim());
        String col = m.group(1).toLowerCase(Locale.ROOT);
        return new Condition(col, m.group(2), parse(m.group(3), t.column(col).kind));
    }

    private static Object parse(String raw, Kind kind) {
        String value = raw.trim();
        if (value.equalsIgnoreCase("NULL")) return null;
        boolean quoted = value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'';
        if (value.indexOf('\'') >= 0 && !quoted) throw new IllegalArgumentException("Неверно оформлена строка: " + value);
        String plain = value;
        if (quoted) {
            StringBuilder decoded = new StringBuilder();
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (c == '\'') {
                    if (i + 1 >= value.length() - 1 || value.charAt(i + 1) != '\'')
                        throw new IllegalArgumentException("Неверно оформлена строка: " + value);
                    i++;
                }
                decoded.append(c);
            }
            plain = decoded.toString();
        }
        try {
            switch (kind) {
                case INT: if (quoted) break; return Integer.valueOf(plain);
                case DOUBLE: if (quoted) break; double d = Double.parseDouble(plain); if (Double.isFinite(d)) return d; break;
                case STRING: if (quoted) return plain; break;
                case DATE: if (quoted) return LocalDate.parse(plain); break;
                case BOOLEAN: if (!quoted && (plain.equalsIgnoreCase("TRUE") || plain.equalsIgnoreCase("FALSE"))) return Boolean.valueOf(plain); break;
            }
        } catch (NumberFormatException | DateTimeParseException ex) { }
        throw new IllegalArgumentException("Неверное значение типа " + kind + ": " + value);
    }

    private static Kind kind(String value) {
        switch (value.toUpperCase(Locale.ROOT)) {
            case "INT": case "INTEGER": return Kind.INT;
            case "DOUBLE": case "FLOAT": return Kind.DOUBLE;
            case "STRING": case "TEXT": return Kind.STRING;
            case "DATE": return Kind.DATE;
            case "BOOLEAN": case "BOOL": return Kind.BOOLEAN;
            default: throw new IllegalArgumentException("Неизвестный тип данных");
        }
    }
    private static boolean starts(String s, String prefix) { return s.regionMatches(true, 0, prefix, 0, prefix.length()); }
    private static int keywordOutsideQuotes(String input, String word) {
        boolean quoted = false;
        for (int i = 0; i <= input.length() - word.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < input.length() && input.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            }
            if (!quoted && input.regionMatches(true, i, word, 0, word.length())
                && (i == 0 || Character.isWhitespace(input.charAt(i - 1)))
                && (i + word.length() == input.length() || Character.isWhitespace(input.charAt(i + word.length())))) return i;
        }
        return -1;
    }
    private static Matcher match(String regex, String s) {
        Matcher m = Pattern.compile(regex).matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("Неверный синтаксис SQL: " + s);
        return m;
    }
    private static List<String> identifiers(String s) {
        List<String> out = new ArrayList<>();
        for (String raw : splitTop(s, ',')) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (!name.matches("\\w+")) throw new IllegalArgumentException("Неверное имя столбца: " + raw);
            out.add(name);
        }
        return out;
    }
    private static List<String> splitTop(String input, char delimiter) {
        List<String> out = new ArrayList<>();
        boolean quote = false;
        int depth = 0, begin = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                if (quote && i + 1 < input.length() && input.charAt(i + 1) == '\'') i++;
                else quote = !quote;
            } else if (!quote) {
                if (c == '(') depth++;
                else if (c == ')') { if (--depth < 0) throw new IllegalArgumentException("Несогласованные скобки"); }
                else if (c == delimiter && depth == 0) { out.add(input.substring(begin, i).trim()); begin = i + 1; }
            }
        }
        if (quote || depth != 0) throw new IllegalArgumentException("Не закрыта кавычка или скобка");
        out.add(input.substring(begin).trim());
        return out;
    }
    private static String format(List<String> headers, List<List<String>> rows) {
        int[] width = new int[headers.size()];
        for (int i = 0; i < width.length; i++) width[i] = headers.get(i).length();
        for (List<String> row : rows) for (int i = 0; i < width.length; i++) width[i] = Math.max(width[i], row.get(i).length());
        StringBuilder b = new StringBuilder();
        line(b, headers, width);
        for (int w : width) { for (int j = 0; j < w + 2; j++) b.append('-'); b.append('+'); }
        b.append('\n');
        for (List<String> row : rows) line(b, row, width);
        b.append("Найдено строк: ").append(rows.size());
        return b.toString();
    }
    private static void line(StringBuilder b, List<String> cells, int[] widths) {
        for (int i = 0; i < cells.size(); i++) {
            b.append(' ').append(cells.get(i));
            for (int j = cells.get(i).length(); j < widths[i] + 1; j++) b.append(' ');
            b.append('|');
        }
        b.append('\n');
    }
}
