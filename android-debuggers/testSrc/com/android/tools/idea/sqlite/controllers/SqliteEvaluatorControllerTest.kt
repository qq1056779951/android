/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.sqlite.controllers

import com.android.testutils.MockitoKt.any
import com.android.testutils.MockitoKt.eq
import com.android.testutils.MockitoKt.refEq
import com.android.tools.idea.concurrency.FutureCallbackExecutor
import com.android.tools.idea.concurrency.pumpEventsAndWaitForFuture
import com.android.tools.idea.concurrency.pumpEventsAndWaitForFutureCancellation
import com.android.tools.idea.sqlite.DatabaseInspectorAnalyticsTracker
import com.android.tools.idea.sqlite.databaseConnection.DatabaseConnection
import com.android.tools.idea.sqlite.databaseConnection.EmptySqliteResultSet
import com.android.tools.idea.sqlite.databaseConnection.SqliteResultSet
import com.android.tools.idea.sqlite.fileType.SqliteTestUtil
import com.android.tools.idea.sqlite.mocks.MockDatabaseInspectorModel
import com.android.tools.idea.sqlite.mocks.MockDatabaseInspectorViewsFactory
import com.android.tools.idea.sqlite.mocks.MockDatabaseRepository
import com.android.tools.idea.sqlite.mocks.MockSqliteEvaluatorView
import com.android.tools.idea.sqlite.mocks.MockSqliteResultSet
import com.android.tools.idea.sqlite.model.ResultSetSqliteColumn
import com.android.tools.idea.sqlite.model.SqliteAffinity
import com.android.tools.idea.sqlite.model.SqliteColumnValue
import com.android.tools.idea.sqlite.model.SqliteDatabaseId
import com.android.tools.idea.sqlite.model.SqliteRow
import com.android.tools.idea.sqlite.model.SqliteSchema
import com.android.tools.idea.sqlite.model.SqliteStatement
import com.android.tools.idea.sqlite.model.SqliteStatementType
import com.android.tools.idea.sqlite.model.SqliteValue
import com.android.tools.idea.sqlite.model.createSqliteStatement
import com.android.tools.idea.sqlite.ui.sqliteEvaluator.SqliteEvaluatorView
import com.android.tools.idea.sqlite.ui.tableView.RowDiffOperation
import com.android.tools.idea.sqlite.utils.getJdbcDatabaseConnection
import com.android.tools.idea.sqlite.utils.toViewColumns
import com.android.tools.idea.testing.runDispatching
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import com.google.wireless.android.sdk.stats.AppInspectionEvent
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.registerServiceInstance
import com.intellij.util.concurrency.EdtExecutorService
import org.jetbrains.ide.PooledThreadExecutor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.Executor

class SqliteEvaluatorControllerTest : PlatformTestCase() {

  private lateinit var sqliteEvaluatorView: MockSqliteEvaluatorView
  private lateinit var mockDatabaseConnection: DatabaseConnection
  private lateinit var edtExecutor: Executor
  private lateinit var sqliteEvaluatorController: SqliteEvaluatorController
  private lateinit var databaseId: SqliteDatabaseId
  private lateinit var viewFactory: MockDatabaseInspectorViewsFactory
  private lateinit var databaseInspectorModel: MockDatabaseInspectorModel
  private lateinit var databaseRepository: MockDatabaseRepository

  private lateinit var successfulInvocationNotificationInvocations: MutableList<String>

  private lateinit var sqliteUtil: SqliteTestUtil
  private var realDatabaseConnection: DatabaseConnection? = null

  override fun setUp() {
    super.setUp()
    edtExecutor = EdtExecutorService.getInstance()
    databaseInspectorModel = MockDatabaseInspectorModel()
    databaseRepository = spy(MockDatabaseRepository(project, edtExecutor))
    mockDatabaseConnection = mock(DatabaseConnection::class.java)
    viewFactory = MockDatabaseInspectorViewsFactory()
    sqliteEvaluatorView = viewFactory.sqliteEvaluatorView

    successfulInvocationNotificationInvocations = mutableListOf()

    sqliteEvaluatorController = SqliteEvaluatorController(
      myProject,
      databaseInspectorModel,
      databaseRepository,
      sqliteEvaluatorView,
      { successfulInvocationNotificationInvocations.add(it) },
      { },
      edtExecutor,
      edtExecutor
    )
    Disposer.register(testRootDisposable, sqliteEvaluatorController)

    databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, mockDatabaseConnection)
    }
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))

    sqliteUtil = SqliteTestUtil(IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture())
    sqliteUtil.setUp()
  }

  override fun tearDown() {
    try {
      if (realDatabaseConnection != null) {
        pumpEventsAndWaitForFuture(realDatabaseConnection!!.close())
      }

      sqliteUtil.tearDown()
    }
    finally {
      super.tearDown()
    }
  }

  fun testSetUp() {
    // Act
    sqliteEvaluatorController.setUp()

    // Assert
    verify(sqliteEvaluatorView).addListener(any(SqliteEvaluatorView.Listener::class.java))
  }

  fun testEvaluateSqlActionQuerySuccess() {
    // Prepare
    val sqlStatement = SqliteStatement(SqliteStatementType.SELECT, "SELECT")
    `when`(mockDatabaseConnection.query(sqlStatement)).thenReturn(Futures.immediateFuture(EmptySqliteResultSet()))

    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, sqlStatement))

    // Assert
    verify(mockDatabaseConnection).query(sqlStatement)
    assertEquals(listOf("The statement was run successfully"), successfulInvocationNotificationInvocations)
  }

  fun testEvaluateSqlActionQueryFailure() {
    // Prepare
    val sqlStatement = SqliteStatement(SqliteStatementType.UNKNOWN, "fake stmt")
    val throwable = Throwable()
    `when`(mockDatabaseConnection.execute(sqlStatement)).thenReturn(Futures.immediateFailedFuture(throwable))

    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, sqlStatement)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(mockDatabaseConnection).execute(sqlStatement)
    verify(sqliteEvaluatorView.tableView).reportError(eq("An error occurred while running the statement"), refEq(throwable))
  }

  fun testEvaluateStatementWithoutParametersDoesntShowParamsBindingDialog() {
    // Prepare
    val parametersBindingDialogView = viewFactory.parametersBindingDialogView
    `when`(mockDatabaseConnection.query(any(SqliteStatement::class.java))).thenReturn(Futures.immediateFuture(EmptySqliteResultSet()))
    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "SELECT * FROM foo WHERE id = 42"))

    // Assert
    verify(parametersBindingDialogView, times(0)).show()
  }

  fun testEvaluateSqlActionSelectFailure() {
    evaluateSqlQueryFailure(SqliteStatementType.SELECT, "SELECT")
  }

  fun testEvaluateSqlActionCreateSuccess() {
    evaluateSqlActionSuccess(SqliteStatementType.UNKNOWN, "CREATE")
  }

  fun testEvaluateSqlActionCreateFailure() {
    evaluateSqlExecuteFailure(SqliteStatementType.UNKNOWN, "CREATE")
  }

  fun testEvaluateSqlActionDropSuccess() {
    evaluateSqlActionSuccess(SqliteStatementType.UNKNOWN, "DROP")
  }

  fun testEvaluateSqlActionDropFailure() {
    evaluateSqlExecuteFailure(SqliteStatementType.UNKNOWN, "DROP")
  }

  fun testEvaluateSqlActionAlterSuccess() {
    evaluateSqlActionSuccess(SqliteStatementType.UNKNOWN, "ALTER")
  }

  fun testEvaluateSqlActionAlterFailure() {
    evaluateSqlExecuteFailure(SqliteStatementType.UNKNOWN, "ALTER")
  }

  fun testEvaluateSqlActionInsertSuccess() {
    evaluateSqlActionSuccess(SqliteStatementType.INSERT, "INSERT")
  }

  fun testEvaluateSqlActionInsertFailure() {
    evaluateSqlExecuteFailure(SqliteStatementType.INSERT, "INSERT")
  }

  fun testEvaluateSqlActionUpdateSuccess() {
    evaluateSqlActionSuccess(SqliteStatementType.UPDATE, "UPDATE")
  }

  fun testEvaluateSqlActionUpdateFailure() {
    evaluateSqlExecuteFailure(SqliteStatementType.UPDATE, "UPDATE")
  }

  fun testEvaluateSqlActionDeleteSuccess() {
    evaluateSqlActionSuccess(SqliteStatementType.DELETE, "DELETE")
  }

  fun testEvaluateSqlActionDeleteFailure() {
    evaluateSqlExecuteFailure(SqliteStatementType.DELETE, "DELETE")
  }

  fun testTableViewIsNotShownForDataManipulationStatements() {
    // Prepare
    `when`(mockDatabaseConnection.execute(SqliteStatement(SqliteStatementType.UPDATE, "fake stmt")))
      .thenReturn(Futures.immediateFuture(Unit))

    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.UPDATE, "fake stmt")
    ))

    // Assert
    verify(sqliteEvaluatorView.tableView, times(0)).updateRows(emptyList())
  }

  fun testTableViewIsShownIfResultSetIsNotEmpty() {
    // Prepare
    val mockSqliteResultSet = MockSqliteResultSet(10)
    `when`(mockDatabaseConnection.query(SqliteStatement(SqliteStatementType.SELECT,"SELECT")))
      .thenReturn(Futures.immediateFuture(mockSqliteResultSet))

    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT, "SELECT")
    ))

    // Assert
    verify(sqliteEvaluatorView.tableView).updateRows(mockSqliteResultSet.rows.map { RowDiffOperation.AddRow(it) })
  }

  fun testTableViewIsShownIfResultSetIsEmpty() {
    // Prepare
    val mockSqliteResultSet = MockSqliteResultSet(0)
    `when`(mockDatabaseConnection.query(SqliteStatement(SqliteStatementType.SELECT,"SELECT")))
      .thenReturn(Futures.immediateFuture(mockSqliteResultSet))

    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT, "SELECT")
    ))

    // Assert
    verify(sqliteEvaluatorView.tableView).updateRows(mockSqliteResultSet.rows.map { RowDiffOperation.AddRow(it) })
  }

  fun testUpdateSchemaIsCalledEveryTimeAUserDefinedStatementIsExecuted() {
    // Prepare
    val mockListener = mock(SqliteEvaluatorController.Listener::class.java)

    sqliteEvaluatorController.setUp()
    sqliteEvaluatorController.addListener(mockListener)

    `when`(mockDatabaseConnection.execute(SqliteStatement(SqliteStatementType.UPDATE,"fake stmt")))
      .thenReturn(Futures.immediateFuture(Unit))

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.UPDATE, "fake stmt")
    ))
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.UPDATE, "fake stmt")
    ))

    // Assert
    verify(mockListener, times(2)).onSqliteStatementExecuted(databaseId)
  }

  fun testResetViewBeforePopulatingIt() {
    // Prepare
    val mockSqliteResultSet = MockSqliteResultSet(10)
    `when`(mockDatabaseConnection.query(SqliteStatement(SqliteStatementType.SELECT, "SELECT")))
      .thenReturn(Futures.immediateFuture(mockSqliteResultSet))

    sqliteEvaluatorController.setUp()

    val orderVerifier = inOrder(sqliteEvaluatorView.tableView)

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT, "SELECT")
    ))
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT, "SELECT")
    ))

    // Assert
    orderVerifier.verify(sqliteEvaluatorView.tableView).showTableColumns(mockSqliteResultSet._columns.toViewColumns())
    orderVerifier.verify(sqliteEvaluatorView.tableView).updateRows(mockSqliteResultSet.rows.map { RowDiffOperation.AddRow(it) })
    orderVerifier.verify(sqliteEvaluatorView.tableView).resetView()
    orderVerifier.verify(sqliteEvaluatorView.tableView).showTableColumns(mockSqliteResultSet._columns.toViewColumns())
    orderVerifier.verify(sqliteEvaluatorView.tableView).updateRows(mockSqliteResultSet.rows.map { RowDiffOperation.AddRow(it) })
  }

  fun testRefreshData() {
    // Prepare
    val mockSqliteResultSet = MockSqliteResultSet(10)
    `when`(mockDatabaseConnection.query(SqliteStatement(SqliteStatementType.SELECT, "SELECT")))
      .thenReturn(Futures.immediateFuture(mockSqliteResultSet))

    sqliteEvaluatorController.setUp()
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT,"SELECT")
    ))

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.refreshData())

    // Assert
    verify(sqliteEvaluatorView.tableView, times(2)).startTableLoading()
  }

  fun testRefreshDataScheduledOneAtATime() {
    // Prepare
    val mockSqliteResultSet = MockSqliteResultSet(10)
    `when`(mockDatabaseConnection.query(SqliteStatement(SqliteStatementType.SELECT, "SELECT")))
      .thenReturn(Futures.immediateFuture(mockSqliteResultSet))

    sqliteEvaluatorController.setUp()
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT, "SELECT")
    ))

    // Act
    val future1 = sqliteEvaluatorController.refreshData()
    val future2 = sqliteEvaluatorController.refreshData()
    pumpEventsAndWaitForFuture(future2)
    val future3 = sqliteEvaluatorController.refreshData()

    // Assert
    assertEquals(future1, future2)
    assertTrue(future2 != future3)
  }

  fun testDisposeCancelsExecution() {
    // Prepare
    val executeFuture = SettableFuture.create<Unit>()
    `when`(databaseRepository.executeStatement(databaseId, SqliteStatement(SqliteStatementType.UNKNOWN, "fake stmt")))
      .thenReturn(executeFuture)
    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, SqliteStatement(SqliteStatementType.UNKNOWN, "fake stmt"))
    Disposer.dispose(sqliteEvaluatorController)
    // Assert
    pumpEventsAndWaitForFutureCancellation(executeFuture)
  }

  fun testEvaluateExpressionAnalytics() {
    // Prepare
    val mockTrackerService = mock(DatabaseInspectorAnalyticsTracker::class.java)
    project.registerServiceInstance(DatabaseInspectorAnalyticsTracker::class.java, mockTrackerService)

    `when`(mockDatabaseConnection.execute(any(SqliteStatement::class.java))).thenReturn(Futures.immediateFuture(Unit))
    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorView.listeners.first().evaluateCurrentStatement()

    // Assert
    verify(mockTrackerService).trackStatementExecuted(AppInspectionEvent.DatabaseInspectorEvent.StatementContext.USER_DEFINED_STATEMENT_CONTEXT)
  }

  fun testNotifyDataMightBeStaleUpdatesTable() {
    // Prepare
    val mockResultSet = MockSqliteResultSet()
    `when`(mockDatabaseConnection.query(any(SqliteStatement::class.java))).thenReturn(Futures.immediateFuture(mockResultSet))
    sqliteEvaluatorController.setUp()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(
      databaseId, SqliteStatement(SqliteStatementType.SELECT, "fake stmt")
    ))

    viewFactory.tableView.listeners.first().toggleLiveUpdatesInvoked()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Act
    sqliteEvaluatorController.notifyDataMightBeStale()
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    // 1st invocation by setUp, 2nd by toggleLiveUpdatesInvoked, 3rd by notifyDataMightBeStale
    verify(sqliteEvaluatorView.tableView, times(3)).showTableColumns(mockResultSet._columns.toViewColumns())
    // invocation by setUp
    verify(sqliteEvaluatorView.tableView, times(1)).updateRows(mockResultSet.invocations[0].map { RowDiffOperation.AddRow(it) })
    // 1st by toggleLiveUpdatesInvoked, 2nd by notifyDataMightBeStale
    verify(sqliteEvaluatorView.tableView, times(2)).updateRows(emptyList())
    // invocation by setUp
    verify(sqliteEvaluatorView.tableView, times(1)).startTableLoading()
  }

  private fun evaluateSqlActionSuccess(sqliteStatementType: SqliteStatementType, sqliteStatement: String) {
    // Prepare
    `when`(mockDatabaseConnection.execute(SqliteStatement(sqliteStatementType, sqliteStatement))).thenReturn(Futures.immediateFuture(Unit))

    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, SqliteStatement(sqliteStatementType, sqliteStatement))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(mockDatabaseConnection).execute(SqliteStatement(sqliteStatementType, sqliteStatement))
    verify(sqliteEvaluatorView.tableView).setEmptyText("The statement was run successfully")
    assertEquals(listOf("The statement was run successfully"), successfulInvocationNotificationInvocations)
  }

  fun testOldTableControllerListenerIsRemoveFromViewWhenNewQueryIsExecuted() {
    // Prepare
    val sqlStatement1 = SqliteStatement(SqliteStatementType.SELECT, "fake stmt1")
    val sqlStatement2 = SqliteStatement(SqliteStatementType.SELECT, "fake stmt2")

    val mockSqliteResultSet1 = MockSqliteResultSet()
    val mockSqliteResultSet2 = MockSqliteResultSet(columns = listOf(ResultSetSqliteColumn("c1")))

    `when`(mockDatabaseConnection.query(sqlStatement1)).thenReturn(Futures.immediateFuture(mockSqliteResultSet1))
    `when`(mockDatabaseConnection.query(sqlStatement2)).thenReturn(Futures.immediateFuture(mockSqliteResultSet2))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, sqlStatement1))
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, sqlStatement2))
    viewFactory.tableView.listeners.forEach { it.refreshDataInvoked() }
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView.tableView).showTableColumns(mockSqliteResultSet1._columns.toViewColumns())
    verify(sqliteEvaluatorView.tableView, times(2)).showTableColumns(mockSqliteResultSet2._columns.toViewColumns())
  }

  fun testRunSelectStatementWithSemicolon() {
    val sqliteFile = sqliteUtil.createAdHocSqliteDatabase(
      "db",
      "create table t1 (c1 int)",
      "insert into t1 values (42)"
    )
    realDatabaseConnection = pumpEventsAndWaitForFuture(
      getJdbcDatabaseConnection(testRootDisposable, sqliteFile, FutureCallbackExecutor.wrap(PooledThreadExecutor.INSTANCE))
    )
    val databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, realDatabaseConnection!!)
    }
    val sqliteRow = SqliteRow(listOf(SqliteColumnValue("c1", SqliteValue.fromAny(42))))
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "SELECT * FROM t1;")))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView).showSqliteStatement("SELECT * FROM t1;")
    verify(sqliteEvaluatorView.tableView).showTableColumns(
      listOf(ResultSetSqliteColumn("c1", SqliteAffinity.INTEGER, true, false)).toViewColumns()
    )
    verify(sqliteEvaluatorView.tableView).updateRows(listOf(RowDiffOperation.AddRow(sqliteRow)))
  }

  fun testRunPragmaStatement() {
    val sqliteFile = sqliteUtil.createAdHocSqliteDatabase(
      "db",
      "create table t1 (c1 int)",
      "insert into t1 values (42)"
    )
    realDatabaseConnection = pumpEventsAndWaitForFuture(
      getJdbcDatabaseConnection(testRootDisposable, sqliteFile, FutureCallbackExecutor.wrap(PooledThreadExecutor.INSTANCE))
    )
    val databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, realDatabaseConnection!!)
    }
    val sqliteRow = SqliteRow(listOf(SqliteColumnValue("c1", SqliteValue.fromAny(42))))
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "pragma table_info('sqlite_master')")))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView).showSqliteStatement("pragma table_info('sqlite_master')")
    verify(sqliteEvaluatorView.tableView).showTableColumns(any())
    verify(sqliteEvaluatorView.tableView).updateRows(any())
  }

  fun testRunPragmaStatementSetVariable() {
    val sqliteFile = sqliteUtil.createAdHocSqliteDatabase(
      "db",
      "create table t1 (c1 int)",
      "insert into t1 values (42)"
    )
    realDatabaseConnection = pumpEventsAndWaitForFuture(
      getJdbcDatabaseConnection(testRootDisposable, sqliteFile, FutureCallbackExecutor.wrap(PooledThreadExecutor.INSTANCE))
    )
    val databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, realDatabaseConnection!!)
    }
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "PRAGMA cache_size = 2")))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView).showSqliteStatement("PRAGMA cache_size = 2")
    assertEquals(listOf("The statement was run successfully"), successfulInvocationNotificationInvocations)
  }

  fun testRunInsertStatementWithSemicolon() {
    val sqliteFile = sqliteUtil.createAdHocSqliteDatabase(
      "db",
      "create table t1 (c1 int)",
      "insert into t1 values (42)"
    )
    realDatabaseConnection = pumpEventsAndWaitForFuture(
      getJdbcDatabaseConnection(testRootDisposable, sqliteFile, FutureCallbackExecutor.wrap(PooledThreadExecutor.INSTANCE))
    )
    val databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, realDatabaseConnection!!)
    }
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "INSERT INTO t1 VALUES (0);")))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView).showSqliteStatement("INSERT INTO t1 VALUES (0);")
    verify(sqliteEvaluatorView.tableView).setEmptyText("The statement was run successfully")
    assertEquals(listOf("The statement was run successfully"), successfulInvocationNotificationInvocations)
  }

  fun testRunSelectStatementWithoutSemicolon() {
    val sqliteFile = sqliteUtil.createAdHocSqliteDatabase(
      "db",
      "create table t1 (c1 int)",
      "insert into t1 values (42)"
    )
    realDatabaseConnection = pumpEventsAndWaitForFuture(
      getJdbcDatabaseConnection(testRootDisposable, sqliteFile, FutureCallbackExecutor.wrap(PooledThreadExecutor.INSTANCE))
    )
    val databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, realDatabaseConnection!!)
    }
    val sqliteRow = SqliteRow(listOf(SqliteColumnValue("c1", SqliteValue.fromAny(42))))
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "SELECT * FROM t1")))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView).showSqliteStatement("SELECT * FROM t1")
    verify(sqliteEvaluatorView.tableView).showTableColumns(
      listOf(ResultSetSqliteColumn("c1", SqliteAffinity.INTEGER, true, false)).toViewColumns()
    )
    verify(sqliteEvaluatorView.tableView).updateRows(listOf(RowDiffOperation.AddRow(sqliteRow)))
  }

  fun testRunSelectStatementWithTrailingLineComment() {
    val sqliteFile = sqliteUtil.createAdHocSqliteDatabase(
      "db",
      "create table t1 (c1 int)",
      "insert into t1 values (42)"
    )
    realDatabaseConnection = pumpEventsAndWaitForFuture(
      getJdbcDatabaseConnection(testRootDisposable, sqliteFile, FutureCallbackExecutor.wrap(PooledThreadExecutor.INSTANCE))
    )
    val databaseId = SqliteDatabaseId.fromLiveDatabase("db", 1)
    val sqliteRow = SqliteRow(listOf(SqliteColumnValue("c1", SqliteValue.fromAny(42))))
    runDispatching {
      databaseRepository.addDatabaseConnection(databaseId, realDatabaseConnection!!)
    }
    databaseInspectorModel.addDatabaseSchema(databaseId, SqliteSchema(emptyList()))
    sqliteEvaluatorController.setUp()

    // Act
    pumpEventsAndWaitForFuture(sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, createSqliteStatement(project, "SELECT * FROM t1 --comment")))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(sqliteEvaluatorView).showSqliteStatement("SELECT * FROM t1 --comment")
    verify(sqliteEvaluatorView.tableView).showTableColumns(
      listOf(ResultSetSqliteColumn("c1", SqliteAffinity.INTEGER, true, false)).toViewColumns()
    )
    verify(sqliteEvaluatorView.tableView).updateRows(listOf(RowDiffOperation.AddRow(sqliteRow)))
  }

  fun testSqliteStatementChangedEnablesRunStatement() {
    // Prepare
    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorView.listeners.first().sqliteStatementTextChangedInvoked("SELECT * FROM tab")

    // Assert
    verify(sqliteEvaluatorView).setRunSqliteStatementEnabled(true)
  }

  fun testSqliteStatementChangedDisablesRunStatement() {
    // Prepare
    sqliteEvaluatorController.setUp()

    // Initially disabled
    verify(sqliteEvaluatorView).setRunSqliteStatementEnabled(false)

    // Act
    sqliteEvaluatorView.listeners.first().sqliteStatementTextChangedInvoked("random string")

    // Assert
    verify(sqliteEvaluatorView, times(2)).setRunSqliteStatementEnabled(false)
  }

  fun testRemoveAllDbsDisablesRunStatement() {
    // Prepare
    sqliteEvaluatorController.setUp()
    verify(sqliteEvaluatorView).setRunSqliteStatementEnabled(false)

    sqliteEvaluatorView.listeners.first().sqliteStatementTextChangedInvoked("Select * FROM foo")
    verify(sqliteEvaluatorView).setRunSqliteStatementEnabled(true)

    // Act
    databaseInspectorModel.removeDatabaseSchema(databaseId)

    // Assert
    verify(sqliteEvaluatorView, times(2)).setRunSqliteStatementEnabled(false)
  }

  private fun evaluateSqlExecuteFailure(sqliteStatementType: SqliteStatementType, sqliteStatement: String) {
    // Prepare
    val throwable = Throwable()
    `when`(mockDatabaseConnection.execute(SqliteStatement(sqliteStatementType, sqliteStatement)))
      .thenReturn(Futures.immediateFailedFuture(throwable))

    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, SqliteStatement(sqliteStatementType, sqliteStatement))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(mockDatabaseConnection).execute(SqliteStatement(sqliteStatementType, sqliteStatement))
    verify(sqliteEvaluatorView.tableView).reportError(eq("An error occurred while running the statement"), refEq(throwable))
    verify(sqliteEvaluatorView.tableView).setEmptyText("An error occurred while running the statement")
  }

  private fun evaluateSqlQueryFailure(sqliteStatementType: SqliteStatementType, sqliteStatement: String) {
    // Prepare
    val throwable = Throwable()
    val resultSet = mock(SqliteResultSet::class.java)
    `when`(resultSet.columns).thenReturn(Futures.immediateFailedFuture(throwable))
    `when`(resultSet.totalRowCount).thenReturn(Futures.immediateFailedFuture(throwable))
    `when`(resultSet.getRowBatch(any(), any())).thenReturn(Futures.immediateFailedFuture(throwable))

    `when`(mockDatabaseConnection.query(SqliteStatement(sqliteStatementType, sqliteStatement)))
      .thenReturn(Futures.immediateFuture(resultSet))

    sqliteEvaluatorController.setUp()

    // Act
    sqliteEvaluatorController.showAndExecuteSqlStatement(databaseId, SqliteStatement(sqliteStatementType, sqliteStatement))
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    // Assert
    verify(mockDatabaseConnection).query(SqliteStatement(sqliteStatementType, sqliteStatement))
    verify(sqliteEvaluatorView.tableView).setEmptyText("An error occurred while running the statement")
  }
}
