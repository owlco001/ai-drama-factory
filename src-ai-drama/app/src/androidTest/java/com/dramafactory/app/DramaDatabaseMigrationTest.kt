package com.dramafactory.app

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dramafactory.app.data.DramaDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Real SQLite migration chain smoke test; runs on device/emulator. */
@RunWith(AndroidJUnit4::class)
class DramaDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule
    val helper = MigrationTestHelper(context, DramaDatabase::class.java)

    @Test
    fun migrate1To7_preservesLegacyProjectAndAsset() {
        val db = helper.createDatabase("migration-test", 1)
        db.execSQL("INSERT INTO projects(project_id,name,style_preset,episode_plan,budget_shots,created_at) VALUES('p1','legacy','cinema',1,50,1)")
        db.execSQL("INSERT INTO assets(asset_id,project_id,kind,prompt,updated_at) VALUES('a1','p1','character','legacy prompt',1)")
        db.close()
        helper.runMigrationsAndValidate("migration-test", 7, true, *listOf(
            DramaDatabase.MIGRATION_1_2, DramaDatabase.MIGRATION_2_3, DramaDatabase.MIGRATION_3_4,
            DramaDatabase.MIGRATION_4_5, DramaDatabase.MIGRATION_5_6, DramaDatabase.MIGRATION_6_7,
        ).toTypedArray()).use { migrated: SupportSQLiteDatabase ->
            migrated.query("SELECT name FROM projects WHERE project_id='p1'").use { c ->
                check(c.moveToFirst())
                assertEquals("legacy", c.getString(0))
            }
            migrated.query("SELECT prompt FROM assets WHERE asset_id='a1'").use { c ->
                check(c.moveToFirst())
                assertEquals("legacy prompt", c.getString(0))
            }
        }
    }
}
