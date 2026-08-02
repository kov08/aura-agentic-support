package org.aura.aura.store;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.config.EmbeddingProperties;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The startup tripwire for the vector WIDTH: asserts that {@code aura.embedding.dimension} is the
 * dimension the live {@code kb_chunks.embedding} column actually declares, and refuses to boot if it
 * is not.
 *
 * <h2>Why this check has to exist at all</h2>
 * The number 1024 is written down three times, in three languages that cannot read each other:
 * {@code vector(1024)} in {@code V2__create_kb_chunks.sql}, {@code @Array(length = 1024)} on
 * {@link KbChunk}, and {@code aura.embedding.dimension} in {@code application.yml}. Two of those three
 * are already pinned together — {@code ddl-auto=validate} compares the entity mapping against the live
 * column at every boot. This closes the triangle by pinning the third.
 *
 * <p>What it buys is a failure MODE, not just an earlier failure. A wrong config dimension does not
 * produce a wrong answer; it produces a working application whose ingestion fails halfway through a
 * billable embedding run, or — worse, if the config is only ever read to size a buffer or validate a
 * response — an application that agrees with itself about a number the database has never heard of.
 * The Day 12 lab already measured this family of defect once: when the vectors and the code disagree
 * about their space, nothing throws, nothing warns, and the build stays green.
 *
 * <h2>Why a Flyway callback and not an {@code ApplicationRunner}</h2>
 * Three reasons, in order of weight. It runs at the ONE moment the schema is guaranteed to be current
 * — immediately after {@code migrate()} — so there is no bean-ordering question about whether the
 * table exists yet. Throwing here fails the Flyway bean, which fails the context, which fails the boot
 * before Tomcat ever binds a port; an {@code ApplicationRunner} would fail after the server is up, and
 * would not run under {@code @SpringBootTest} at all, so the check would be untestable in exactly the
 * place it matters. And it needs no {@code DataSource} in its constructor, which means this bean is
 * harmless in the many test contexts that have no database — it is simply never invoked, rather than
 * needing a conditional to keep it from exploding.
 *
 * <p>The consequence to state plainly: with {@code spring.flyway.enabled=false} this check does not
 * run. That is the correct scope — it verifies the schema Flyway wrote, and where Flyway writes
 * nothing there is no claim to verify.
 */
@Slf4j
@Component
public class EmbeddingDimensionCheck implements Callback {

    private static final String TABLE = "kb_chunks";
    private static final String COLUMN = "embedding";

    /**
     * pgvector stores a vector's declared dimension in the column's {@code atttypmod} — the generic
     * "type modifier" slot Postgres gives every type to carry its parameters ({@code varchar(50)}'s
     * 50, {@code numeric(10,2)}'s precision and scale). pgvector's {@code typmod_in} writes the
     * dimension there RAW, with none of the {@code VARHDRSZ} offset that {@code varchar} adds — so
     * {@code atttypmod} is the dimension itself and needs no arithmetic.
     *
     * <p>That last sentence is the kind of claim that is right until it isn't, so it is not taken on
     * trust: {@code PgVectorSchemaIT} runs this exact query against a real migrated table and asserts
     * it returns 1024. If a future pgvector changes the encoding, that test fails loudly rather than
     * this check quietly comparing the wrong number.
     *
     * <p>{@code format_type} is selected alongside purely for the error message — it renders the
     * column's type the way a human would write it ({@code vector(1024)}), which turns a mismatch from
     * two bare integers into something actionable. {@code current_schema()} scopes the lookup to the
     * schema the connection is actually using rather than assuming {@code public}.
     */
    static final String DECLARED_DIMENSION_QUERY = """
            SELECT a.atttypmod AS typmod,
                   format_type(a.atttypid, a.atttypmod) AS declared_type
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema()
              AND c.relname = ?
              AND a.attname = ?
              AND a.attnum > 0
              AND NOT a.attisdropped
            """;

    private final EmbeddingProperties props;

    public EmbeddingDimensionCheck(EmbeddingProperties props) {
        this.props = props;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        // A read-only catalog lookup has nothing to roll back, and wrapping it would only add a
        // transaction whose failure semantics we would then have to reason about.
        return false;
    }

    @Override
    public String getCallbackName() {
        return "embedding-dimension-check";
    }

    @Override
    public void handle(Event event, Context context) {
        int expected = props.dimension();
        int declared;
        String declaredType;

        try (PreparedStatement statement = context.getConnection().prepareStatement(DECLARED_DIMENSION_QUERY)) {
            statement.setString(1, TABLE);
            statement.setString(2, COLUMN);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    // Reachable only if a later migration renames or drops the column while this check
                    // still names the old one. Silence would be the wrong answer — "I could not find
                    // the thing I was asked to verify" is a failure, not a pass.
                    throw new IllegalStateException(
                            "startup check could not find column " + TABLE + "." + COLUMN
                                    + " in the current schema — the vector store's schema and this "
                                    + "check have diverged");
                }
                declared = rows.getInt("typmod");
                declaredType = rows.getString("declared_type");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "startup check could not read the declared dimension of " + TABLE + "." + COLUMN, e);
        }

        if (declared != expected) {
            throw new IllegalStateException(
                    "embedding dimension mismatch: aura.embedding.dimension is " + expected
                            + " but " + TABLE + "." + COLUMN + " is declared " + declaredType
                            + " (dimension " + declared + "). These must agree — a vector store and "
                            + "the application that fills it disagreeing about vector width produces "
                            + "an ingestion that fails partway through a billable embedding run, or "
                            + "silently meaningless similarity scores. Fix the config or add a "
                            + "migration; do not weaken this check.");
        }

        log.info("embedding dimension check — {}.{} is {} and matches aura.embedding.dimension={}",
                TABLE, COLUMN, declaredType, expected);
    }
}
