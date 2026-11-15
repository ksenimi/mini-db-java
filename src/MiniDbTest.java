import java.nio.file.*;

/** Функциональные тесты и воспроизводимое измерение скорости индекса без внешних библиотек. */
public final class MiniDbTest {
    private MiniDbTest() { }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("minidb-test-");
        Path file = dir.resolve("database.bin");
        try {
            MiniDb.Database db = new MiniDb.Database(file);
            assertContains(db.execute("SELECT * FROM cars"), "Найдено строк: 0");
            db.execute("INSERT INTO manufacturers VALUES ('Toyota', 'Japan', '1937-08-28'), ('BMW', 'Germany', '1916-03-07')");
            db.execute("INSERT INTO cars VALUES (1, 'Toyota', 25000.0, TRUE), (2, 'BMW', 28000.0, FALSE)");
            assertContains(db.execute("SELECT brand, price FROM cars WHERE price > 20000"), "Найдено строк: 2");
            assertContains(db.execute("SELECT * FROM manufacturers WHERE founded < '1920-01-01'"), "BMW");
            expectError(db, "INSERT INTO cars (id, brand) VALUES (1, 'Toyota')");
            expectError(db, "INSERT INTO cars (id, brand) VALUES (3, 'Unknown')");
            expectError(db, "INSERT INTO cars (id, brand) VALUES (3, 'Toyota'), (3, 'BMW')");
            assertContains(db.execute("SELECT * FROM cars"), "Найдено строк: 2");
            db.execute("INSERT INTO cars (id, brand) VALUES (3, 'Toyota')");
            assertContains(db.execute("SELECT price, available FROM cars WHERE id = 3"), "NULL");
            expectError(db, "UPDATE cars SET id = 2 WHERE id = 3");
            expectError(db, "UPDATE manufacturers SET brand = 'Honda' WHERE brand = 'Toyota'");
            expectError(db, "DELETE FROM manufacturers WHERE brand = 'BMW'");
            db.execute("UPDATE cars SET price = 30000, available = TRUE WHERE id = 2");
            assertContains(db.execute("SELECT price FROM cars WHERE id = 2"), "30000.0");
            db.execute("CREATE INDEX idx_cars_brand ON cars (brand)");
            assertContains(db.execute("SELECT id FROM cars WHERE brand = 'Toyota'"), "Найдено строк: 2");
            db = new MiniDb.Database(file);
            assertContains(db.execute("SELECT id FROM cars WHERE brand = 'Toyota'"), "Найдено строк: 2");
            expectError(db, "CREATE INDEX idx_cars_brand ON cars (brand)");
            db.execute("DELETE FROM cars WHERE brand = 'BMW'");
            assertContains(db.execute("SELECT id FROM cars WHERE brand = 'BMW'"), "Найдено строк: 0");
            db.execute("DELETE FROM manufacturers WHERE brand = 'BMW'");
            db.execute("CREATE TABLE events (id INT PRIMARY KEY, title STRING, day DATE, confirmed BOOLEAN, cost DOUBLE)");
            db.execute("INSERT INTO events VALUES (1, 'A, B; C', '2026-11-12', TRUE, 1.5)");
            assertContains(db.execute("SELECT title FROM events WHERE id = 1"), "A, B; C");
            db.execute("UPDATE events SET title = 'A WHERE B' WHERE id = 1");
            assertContains(db.execute("SELECT title FROM events WHERE id = 1"), "A WHERE B");
            db.execute("UPDATE events SET title = 'O''Brien' WHERE id = 1");
            assertContains(db.execute("SELECT title FROM events WHERE id = 1"), "O'Brien");
            db.execute("INSERT INTO events VALUES (2, 'Привет, мир', '2026-11-12', FALSE, 2.0)");
            assertContains(db.execute("SELECT title FROM events WHERE id = 2"), "Привет, мир");
            db = new MiniDb.Database(file);
            assertContains(db.execute("SELECT title FROM events WHERE id = 2"), "Привет, мир");
            expectError(db, "UPDATE events SET title = 'bad'quote' WHERE id = 1");
            db.execute("DROP TABLE events");
            expectError(db, "SELECT * FROM events");
            db.execute("CREATE TABLE groups (id INT PRIMARY KEY, label STRING)");
            db.execute("CREATE TABLE members (id INT PRIMARY KEY, group_id INT, FOREIGN KEY (group_id) REFERENCES groups(id))");
            expectError(db, "INSERT INTO members VALUES (1, 9)");
            expectError(db, "DROP TABLE groups");
            db.execute("DROP TABLE members");
            db.execute("DROP TABLE groups");
            if (MiniDb.splitStatements("SELECT 'x;y' FROM cars; SELECT * FROM cars;").size() != 2)
                throw new AssertionError("Неверное разделение запросов");
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
            System.out.println("Функциональные тесты: пройдены");
            if (args.length > 0 && args[0].equals("benchmark")) benchmark(dir.resolve("benchmark.bin"));
        } finally {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path child : files) Files.deleteIfExists(child);
            }
            Files.deleteIfExists(dir);
        }
    }

    private static void benchmark(Path file) throws Exception {
        MiniDb.Database db = new MiniDb.Database(file);
        StringBuilder makers = new StringBuilder("INSERT INTO manufacturers VALUES ");
        for (int i = 0; i < 100; i++) {
            if (i > 0) makers.append(',');
            makers.append("('Brand").append(i).append("', 'Country', '2000-01-01')");
        }
        db.execute(makers.toString());
        StringBuilder cars = new StringBuilder("INSERT INTO cars VALUES ");
        for (int i = 0; i < 10000; i++) {
            if (i > 0) cars.append(',');
            cars.append('(').append(i).append(", 'Brand").append(i % 100).append("', 12345.0, TRUE)");
        }
        db.execute(cars.toString());
        String sql = "SELECT id FROM cars WHERE brand = 'Brand42'";
        for (int i = 0; i < 20; i++) db.execute(sql);
        long scan = timed(db, sql, 400);
        db.execute("CREATE INDEX idx_cars_brand ON cars (brand)");
        for (int i = 0; i < 20; i++) db.execute(sql);
        long indexed = timed(db, sql, 400);
        System.out.printf("Измерение: 10 000 автомобилей, 400 запросов SELECT, 100 результатов в каждом: перебор %.2f мс; индекс %.2f мс; ускорение %.2f раза%n",
            scan / 1e6, indexed / 1e6, (double) scan / indexed);
    }

    private static long timed(MiniDb.Database db, String sql, int repeats) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            String result = db.execute(sql);
            if (!result.endsWith("Найдено строк: 100")) throw new AssertionError("Неверный результат измерения");
        }
        return System.nanoTime() - start;
    }

    private static void expectError(MiniDb.Database db, String sql) throws Exception {
        try { db.execute(sql); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Ожидалась ошибка для запроса: " + sql);
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) throw new AssertionError("Ожидалось «" + expected + "» в результате:\n" + actual);
    }
}
