/* ###
 * IP: GHIDRA
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
package ghidra.app.plugin.core.debug.gui.register;

import static ghidra.lifecycle.Unfinished.TODO;
import static org.junit.Assert.*;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.*;
import org.junit.experimental.categories.Category;

import com.google.common.collect.Range;

import generic.test.category.NightlyCategory;
import ghidra.app.plugin.core.debug.gui.AbstractGhidraHeadedDebuggerGUITest;
import ghidra.app.plugin.core.debug.gui.action.NoneLocationTrackingSpec;
import ghidra.app.plugin.core.debug.gui.listing.DebuggerListingPlugin;
import ghidra.app.plugin.core.debug.gui.register.DebuggerRegistersProvider.RegisterDataSettingsDialog;
import ghidra.app.plugin.core.debug.gui.register.DebuggerRegistersProvider.RegisterTableColumns;
import ghidra.app.plugin.core.debug.service.editing.DebuggerStateEditingServicePlugin;
import ghidra.app.services.DebuggerStateEditingService;
import ghidra.app.services.DebuggerStateEditingService.StateEditingMode;
import ghidra.app.services.TraceRecorder;
import ghidra.docking.settings.FormatSettingsDefinition;
import ghidra.docking.settings.Settings;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.trace.database.DBTraceUtils;
import ghidra.trace.database.ToyDBTraceBuilder;
import ghidra.trace.database.listing.DBTraceCodeSpace;
import ghidra.trace.model.guest.TracePlatform;
import ghidra.trace.model.listing.*;
import ghidra.trace.model.memory.TraceMemoryFlag;
import ghidra.trace.model.memory.TraceMemorySpace;
import ghidra.trace.model.thread.TraceThread;
import ghidra.util.database.UndoableTransaction;
import ghidra.util.exception.DuplicateNameException;

@Category(NightlyCategory.class) // this may actually be an @PortSensitive test
public class DebuggerRegistersProviderTest extends AbstractGhidraHeadedDebuggerGUITest {

	protected DebuggerRegistersPlugin registersPlugin;
	protected DebuggerRegistersProvider registersProvider;
	protected DebuggerListingPlugin listingPlugin;
	protected DebuggerStateEditingService editingService;

	protected Register r0;
	protected Register pc;
	protected Register sp;
	protected Register contextreg;

	protected Register r0h;
	protected Register r0l;
	protected Register pch;
	protected Register pcl;

	protected Set<Register> baseRegs;

	protected StructureDataType r0Struct;

	@Before
	public void setUpRegistersProviderTest() throws Exception {
		registersPlugin = addPlugin(tool, DebuggerRegistersPlugin.class);
		registersProvider = waitForComponentProvider(DebuggerRegistersProvider.class);
		listingPlugin = addPlugin(tool, DebuggerListingPlugin.class);
		editingService = addPlugin(tool, DebuggerStateEditingServicePlugin.class);

		createTrace();
		r0 = tb.language.getRegister("r0");
		pc = tb.language.getProgramCounter();
		sp = tb.language.getDefaultCompilerSpec().getStackPointer();
		contextreg = tb.language.getContextBaseRegister();

		pch = tb.language.getRegister("pch");
		pcl = tb.language.getRegister("pcl");

		r0h = tb.language.getRegister("r0h");
		r0l = tb.language.getRegister("r0l");

		r0Struct = new StructureDataType("r0_struct", 0);
		r0Struct.add(SignedDWordDataType.dataType, "hi", "");
		r0Struct.add(DWordDataType.dataType, "lo", "");

		baseRegs = tb.language.getRegisters()
				.stream()
				.filter(Register::isBaseRegister)
				.collect(Collectors.toSet());
	}

	protected TraceThread addThread() throws DuplicateNameException {
		return addThread("Thread1");
	}

	protected TraceThread addThread(String threadName) throws DuplicateNameException {
		try (UndoableTransaction tid = tb.startTransaction()) {
			return tb.trace.getThreadManager().createThread(threadName, 0);
		}
	}

	protected TracePlatform getPlatform() {
		return tb.host;
	}

	protected void activateThread(TraceThread thread) {
		traceManager.activateThread(thread);
	}

	protected void addRegisterValues(TraceThread thread) {
		try (UndoableTransaction tid = tb.startTransaction()) {
			addRegisterValues(thread, tid);
		}
	}

	protected void addRegisterValues(TraceThread thread, UndoableTransaction tid) {
		TraceMemorySpace regVals =
			tb.trace.getMemoryManager().getMemoryRegisterSpace(thread, true);
		regVals.putBytes(0, pc, tb.buf(0, 0, 0, 0, 0, 0x40, 0, 0));
		regVals.putBytes(0, sp, tb.buf(0x1f, 0, 0, 0, 0, 0, 0, 0));
		regVals.putBytes(0, r0, tb.buf(1, 2, 3, 4, 5, 6, 7, 8));
	}

	protected void addRegisterTypes(TraceThread thread, UndoableTransaction tid)
			throws CodeUnitInsertionException {
		TraceCodeSpace regCode =
			tb.trace.getCodeManager().getCodeRegisterSpace(thread, true);
		regCode.definedData().create(Range.atLeast(0L), pc, PointerDataType.dataType);
		// TODO: Pointer needs to be to ram, not register space
		regCode.definedData().create(Range.atLeast(0L), r0, r0Struct);
	}

	protected void addRegisterTypes(TraceThread thread) throws CodeUnitInsertionException {
		try (UndoableTransaction tid = tb.startTransaction()) {
			addRegisterTypes(thread, tid);
		}
	}

	protected RegisterRow findRegisterRow(Register reg) {
		RegisterRow row = getRegisterRow(reg);
		if (row == null) {
			throw new NoSuchElementException(reg.getName());
		}
		return row;
	}

	protected RegisterRow getRegisterRow(Register reg) {
		return registersProvider.regMap.get(reg);
	}

	protected void setRowText(RegisterRow row, String text) {
		assertTrue(row.isValueEditable());
		row.setValue(new BigInteger(text, 16));
	}

	protected void setRowRepr(RegisterRow row, String repr) {
		assertTrue(row.isRepresentationEditable());
		row.setRepresentation(repr);
	}

	protected void assertRowValueEmpty(RegisterRow row) {
		assertEquals(BigInteger.ZERO, row.getValue());
	}

	protected void assertRowTypeEmpty(RegisterRow row) {
		assertNull(row.getDataType());
	}

	protected void assertPCRowValueEmpty() {
		assertRowValueEmpty(findRegisterRow(pc));
	}

	protected void assertPCRowTypeEmpty() {
		assertRowTypeEmpty(findRegisterRow(pc));
	}

	protected void assertR0RowValueEmpty() {
		assertRowValueEmpty(findRegisterRow(r0));
	}

	protected void assertR0RowTypeEmpty() {
		assertRowTypeEmpty(findRegisterRow(r0));
	}

	protected void assertPCRowValuePopulated() {
		RegisterRow row = findRegisterRow(pc);
		assertEquals(0x00400000, row.getValue().longValue());

		RegisterRow rowH = findRegisterRow(pch);
		assertEquals(0x00000000, rowH.getValue().longValue());

		RegisterRow rowL = findRegisterRow(pcl);
		assertEquals(0x00400000, rowL.getValue().longValue());
	}

	protected void assertPCRowTypePopulated() {
		RegisterRow row = findRegisterRow(pc);
		assertTypeEquals(PointerDataType.dataType, row.getDataType());

		//assertTrue(row.data.getValue() instanceof Address);
		//Address pcAddr = (Address) row.data.getValue();
		//assertEquals("ram", pcAddr.getAddressSpace().getName());
		//assertEquals(0x00400000, pcAddr.getOffset());
		//assertEquals("<INVALID>", row.reprField.getText()); // No memory layout is provided

		RegisterRow rowH = findRegisterRow(pch);
		assertNull(rowH.getDataType());

		RegisterRow rowL = findRegisterRow(pcl);
		assertNull(rowL.getDataType());
	}

	protected void assertR0RowValuePopulated() {
		RegisterRow row = findRegisterRow(r0);
		assertEquals(0x0102030405060708L, row.getValue().longValue());

		RegisterRow rowL = findRegisterRow(r0l);
		assertEquals(0x05060708, rowL.getValue().longValue());

		RegisterRow rowH = findRegisterRow(r0h);
		assertEquals(0x01020304, rowH.getValue().longValue());
	}

	protected void assertR0RowTypePopulated() {
		RegisterRow row = findRegisterRow(r0);
		assertTypeEquals(r0Struct, row.getDataType());

		RegisterRow rowL = findRegisterRow(r0l);
		assertTypeEquals(DWordDataType.dataType, rowL.getDataType());

		RegisterRow rowH = findRegisterRow(r0h);
		assertTypeEquals(SignedDWordDataType.dataType, rowH.getDataType());
	}

	@Test
	public void testEmpty() throws Exception {
		traceManager.openTrace(tb.trace);

		assertEquals(0, registersProvider.regsTableModel.getModelData().size());
	}

	@Test
	public void testNoValuesTypes() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		assertPCRowValueEmpty();
		assertR0RowValueEmpty();
		assertPCRowTypeEmpty();
		assertR0RowTypeEmpty();
	}

	@Test
	public void testDefaultSelection() throws Exception {
		traceManager.openTrace(tb.trace);

		// TODO: Incorporate user settings to remember selection
		// TODO: Use another language to test effect of recorded non-common registers
		TraceThread thread = addThread();
		addRegisterValues(thread);
		activateThread(thread);
		waitForSwing();

		assertEquals(
			DebuggerRegistersProvider.collectCommonRegisters(tb.trace.getBaseCompilerSpec()),
			registersProvider.getSelectionFor(tb.host));
	}

	@Test
	public void testAddValuesThenActivatePopulatesPanel() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		addRegisterValues(thread);
		activateThread(thread);
		waitForDomainObject(tb.trace);

		assertPCRowValuePopulated();
		assertR0RowValuePopulated();
	}

	@Test
	public void testLiveAddValuesThenActivatePopulatesPanel() throws Throwable {
		TraceRecorder recorder = recordAndWaitSync();
		traceManager.openTrace(recorder.getTrace());
		waitForSwing();

		mb.testBank1.writeRegister("pc", new byte[] { 0x00, 0x40, 0x00, 0x00 });
		waitForSwing();

		activateThread(recorder.getTraceThread(mb.testThread1));
		waitForSwing();

		assertPCRowValuePopulated();
	}

	@Test
	public void testLiveActivateThenAddValuesPopulatesPanel() throws Throwable {
		TraceRecorder recorder = recordAndWaitSync();
		traceManager.openTrace(recorder.getTrace());
		activateThread(recorder.getTraceThread(mb.testThread1));
		waitForSwing();

		mb.testBank1.writeRegister("pc", new byte[] { 0x00, 0x40, 0x00, 0x00 });
		waitOn(mb.testModel.flushEvents());
		waitForDomainObject(recorder.getTrace());

		RegisterRow rowL = findRegisterRow(pc);
		waitForPass(() -> assertTrue(rowL.isKnown()));
		assertPCRowValuePopulated();
	}

	@Test
	public void testActivateThenAddValuesPopulatesPanel() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		addRegisterValues(thread);
		waitForDomainObject(tb.trace);

		assertPCRowValuePopulated();
		assertR0RowValuePopulated();
	}

	@Test
	public void testAddTypesThenActivatePopulatesPanel() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		addRegisterValues(thread);
		addRegisterTypes(thread);
		activateThread(thread);
		waitForSwing();

		assertPCRowTypePopulated();
		assertR0RowTypePopulated();
	}

	@Test
	public void testActivateThenAddTypesPopulatesPanel() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);

		addRegisterValues(thread);
		addRegisterTypes(thread);
		waitForDomainObject(tb.trace);

		assertPCRowTypePopulated();
		assertR0RowTypePopulated();
	}

	// TODO: Test that contextreg cannot be modified
	// TODO: Make contextreg modifiable by Registers window

	@Test
	public void testModifyValueEmu() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		editingService.setCurrentMode(tb.trace, StateEditingMode.WRITE_EMULATOR);

		assertTrue(registersProvider.actionEnableEdits.isEnabled());
		performAction(registersProvider.actionEnableEdits);

		addRegisterValues(thread);
		waitForDomainObject(tb.trace);

		TraceMemorySpace regVals =
			tb.trace.getMemoryManager().getMemoryRegisterSpace(thread, false);

		RegisterRow row = findRegisterRow(r0);

		setRowText(row, "1234");
		waitForSwing();
		waitForPass(() -> {
			long viewSnap = traceManager.getCurrent().getViewSnap();
			assertTrue(DBTraceUtils.isScratch(viewSnap));
			assertEquals(BigInteger.valueOf(0x1234),
				regVals.getValue(getPlatform(), viewSnap, r0).getUnsignedValue());
			assertEquals(BigInteger.valueOf(0x1234), row.getValue());
		});
	}

	long encodeDouble(double value) {
		ByteBuffer buf = ByteBuffer.allocate(Double.BYTES);
		buf.putDouble(0, value);
		return buf.getLong(0);
	}

	@Test
	public void testModifyRepresentationEmu() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		editingService.setCurrentMode(tb.trace, StateEditingMode.WRITE_EMULATOR);

		assertTrue(registersProvider.actionEnableEdits.isEnabled());
		performAction(registersProvider.actionEnableEdits);

		addRegisterValues(thread);
		waitForDomainObject(tb.trace);

		TraceMemorySpace regVals =
			tb.trace.getMemoryManager().getMemoryRegisterSpace(thread, false);

		RegisterRow row = findRegisterRow(r0);
		assertFalse(row.isRepresentationEditable());

		row.setDataType(DoubleDataType.dataType);
		waitForDomainObject(tb.trace);

		setRowRepr(row, "1234");
		waitForSwing();
		waitForPass(() -> {
			long viewSnap = traceManager.getCurrent().getViewSnap();
			assertTrue(DBTraceUtils.isScratch(viewSnap));
			assertEquals(BigInteger.valueOf(encodeDouble(1234)),
				regVals.getValue(getPlatform(), viewSnap, r0).getUnsignedValue());
			assertEquals(BigInteger.valueOf(encodeDouble(1234)), row.getValue());
		});
	}

	// NOTE: Value modification only allowed on live target

	@Test
	public void testModifyTypeAffectsTrace() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		RegisterRow row = findRegisterRow(pc);
		row.setDataType(PointerDataType.dataType);
		waitForSwing();

		DBTraceCodeSpace regCode =
			tb.trace.getCodeManager().getCodeRegisterSpace(thread, false);
		assertNotNull(regCode);
		TraceData data = regCode.data().getForRegister(getPlatform(), 0L, pc);
		assertTypeEquals(PointerDataType.dataType, data.getDataType());
	}

	@Test
	public void testModifyTypeSettingsAffectsTrace() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		try (UndoableTransaction tid = tb.startTransaction()) {
			tb.exec(getPlatform(), 0, thread, 0, "pc = 100;");
		}
		activateThread(thread);
		waitForSwing();

		RegisterRow row = findRegisterRow(pc);
		row.setDataType(LongLongDataType.dataType);
		waitForSwing();

		DBTraceCodeSpace regCode =
			tb.trace.getCodeManager().getCodeRegisterSpace(thread, false);
		assertNotNull(regCode);
		TraceData data = regCode.data().getForRegister(getPlatform(), 0L, pc);
		assertTypeEquals(LongLongDataType.dataType, data.getDataType());
		assertEquals("64h", row.getRepresentation());

		registersProvider.regsFilterPanel.setSelectedItem(row);
		waitForSwing();
		performEnabledAction(registersProvider, registersProvider.actionDataTypeSettings, false);
		RegisterDataSettingsDialog dialog =
			waitForDialogComponent(RegisterDataSettingsDialog.class);
		Settings settings = dialog.getSettings();
		FormatSettingsDefinition format = FormatSettingsDefinition.DEF;
		format.setChoice(settings, FormatSettingsDefinition.DECIMAL);
		runSwing(() -> dialog.okCallback());

		// The data is the settings. Wonderful :/
		assertEquals(FormatSettingsDefinition.DECIMAL, format.getChoice(data));
		assertEquals("100", row.getRepresentation());
	}

	@Test
	public void testModifySubRegTypeAffectsTrace() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		RegisterRow rowL = findRegisterRow(r0l);
		rowL.setDataType(DWordDataType.dataType);
		RegisterRow rowH = findRegisterRow(r0h);
		rowH.setDataType(SignedDWordDataType.dataType);
		waitForSwing();

		DBTraceCodeSpace regCode =
			tb.trace.getCodeManager().getCodeRegisterSpace(thread, false);
		assertNotNull(regCode);
		// It's two units, not a struct with two components
		TracePlatform platform = getPlatform();
		assertNull(regCode.data().getForRegister(platform, 0L, r0));
		TraceData dataL = regCode.data().getForRegister(platform, 0L, r0l);
		assertTypeEquals(PointerDataType.dataType, dataL.getDataType());
		TraceData dataH = regCode.data().getForRegister(platform, 0L, r0h);
		assertTypeEquals(PointerDataType.dataType, dataH.getDataType());
	}

	@Test
	public void testUndoRedo() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		// Group adds into a single transaction
		try (UndoableTransaction tid = tb.startTransaction()) {
			addRegisterValues(thread, tid);
			addRegisterTypes(thread, tid);
		}
		waitForSwing();

		// Sanity
		assertPCRowValuePopulated();
		assertR0RowValuePopulated();
		assertPCRowTypePopulated();
		assertR0RowTypePopulated();

		undo(tb.trace);

		assertPCRowValueEmpty();
		assertR0RowValueEmpty();
		assertPCRowTypeEmpty();
		assertR0RowTypeEmpty();

		redo(tb.trace);

		assertPCRowValuePopulated();
		assertR0RowValuePopulated();
		assertPCRowTypePopulated();
		assertR0RowTypePopulated();
	}

	@Test
	public void testAbort() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);

		try (UndoableTransaction tid = tb.startTransaction()) {
			addRegisterValues(thread, tid);
			addRegisterTypes(thread, tid);
			waitForSwing();

			// Sanity
			assertPCRowValuePopulated();
			assertR0RowValuePopulated();
			assertPCRowTypePopulated();
			assertR0RowTypePopulated();

			tid.abort();
		}
		waitForDomainObject(tb.trace);

		assertPCRowValueEmpty();
		assertR0RowValueEmpty();
		assertPCRowTypeEmpty();
		assertR0RowTypeEmpty();
	}

	@Test
	public void testActionEnableEdits() throws Exception {
		traceManager.openTrace(tb.trace);
		TraceThread thread = addThread();
		waitForSwing();

		assertFalse(registersProvider.actionEnableEdits.isEnabled());

		activateThread(thread);
		waitForSwing();

		assertTrue(registersProvider.actionEnableEdits.isEnabled());

		// NB, can't activate "null" trace. Manager ignores it.
		traceManager.closeTrace(tb.trace);
		waitForSwing();

		assertFalse(registersProvider.actionEnableEdits.isEnabled());
	}

	@Test
	public void testActivateThenAddValuesTypesInFutureHasNoEffect() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		addRegisterValues(thread);
		activateThread(thread);
		waitForDomainObject(tb.trace);

		assertEquals(0, traceManager.getCurrentSnap());
		assertPCRowValuePopulated();
		assertR0RowValuePopulated();

		try (UndoableTransaction tid = tb.startTransaction()) {
			TraceMemorySpace regVals =
				tb.trace.getMemoryManager().getMemoryRegisterSpace(thread, true);
			TraceCodeSpace regCode =
				tb.trace.getCodeManager().getCodeRegisterSpace(thread, true);
			TracePlatform platform = getPlatform();
			regVals.putBytes(platform, 1, r0, tb.buf(1, 1, 2, 2, 3, 3, 4, 4));
			regCode.definedData().create(platform, Range.atLeast(1L), r0, r0Struct);
		}
		waitForDomainObject(tb.trace);

		assertR0RowValuePopulated();
		assertR0RowTypeEmpty();
	}

	@Test
	public void testActivateAdvanceThenShortenTypeLifespan() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		addRegisterValues(thread);
		addRegisterTypes(thread);
		activateThread(thread);
		traceManager.activateSnap(1);
		waitForDomainObject(tb.trace);

		assertEquals(1, traceManager.getCurrentSnap());
		assertR0RowTypePopulated();

		try (UndoableTransaction tid = tb.startTransaction()) {
			TraceCodeSpace regCode =
				tb.trace.getCodeManager().getCodeRegisterSpace(thread, true);
			TraceCodeUnit code = regCode.codeUnits().getContaining(getPlatform(), 1, r0);
			code.setEndSnap(0);
		}
		waitForDomainObject(tb.trace);

		assertR0RowTypeEmpty();
	}

	@Test
	@Ignore("DBTrace not well tested with type replacement, yet")
	public void testActivateThenReplaceType() throws Exception {
		TODO();
		// NOTE: could get complicated if size changes
	}

	@Test
	public void testActivateThenRemoveType() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		addRegisterValues(thread);
		addRegisterTypes(thread);
		activateThread(thread);
		waitForDomainObject(tb.trace);

		assertR0RowTypePopulated();

		try (UndoableTransaction tid = tb.startTransaction()) {
			TraceCodeSpace regCode =
				tb.trace.getCodeManager().getCodeRegisterSpace(thread, true);
			TraceCodeUnit code = regCode.codeUnits().getContaining(getPlatform(), 1, r0);
			code.delete();
		}
		waitForDomainObject(tb.trace);

		assertR0RowTypeEmpty();
	}

	@Test
	public void testActionCreateSnapshot() throws Exception {
		assertFalse(registersProvider.actionCreateSnapshot.isEnabled());

		TraceThread thread1 = addThread();
		TraceThread thread2 = addThread("Thread2");
		addRegisterValues(thread1);
		addRegisterTypes(thread1);
		traceManager.openTrace(tb.trace);
		waitForDomainObject(tb.trace);

		assertFalse(registersProvider.actionCreateSnapshot.isEnabled());

		activateThread(thread1);
		waitForSwing();

		assertTrue(registersProvider.actionCreateSnapshot.isEnabled());

		performAction(registersProvider.actionCreateSnapshot);
		waitForSwing();

		// TODO: It'd be nice if plugin tracked disconnected providers....
		DebuggerRegistersProvider cloned =
			(DebuggerRegistersProvider) tool.getActiveComponentProvider();
		assertEquals("[Registers]", cloned.getTitle());
		assertEquals("Thread1", cloned.getSubTitle());

		activateThread(thread2);
		waitForSwing();

		assertEquals(thread2, registersProvider.current.getThread());
		assertEquals(thread1, cloned.current.getThread());

		traceManager.activateSnap(1);
		waitForSwing();

		assertEquals(1, registersProvider.current.getSnap());
		assertEquals(0, cloned.current.getSnap()); // TODO: Action to toggle snap tracking?

		// NB, can't activate "null" trace. Manager ignores it.
		traceManager.closeTrace(tb.trace);
		waitForSwing();

		assertFalse(registersProvider.actionCreateSnapshot.isEnabled());

		assertNull(registersProvider.current.getThread());
		assertNull(cloned.current.getThread());
		assertFalse(cloned.isInTool());
	}

	// TODO: GoTo via menu (also visibility, by valid address, context, etc.)

	@Test
	public void testClickAddressTypePerformsGoTo() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		try (UndoableTransaction tid = tb.startTransaction()) {
			// Unconventional start, to ensure goto PC is actually the cause, not just min of view
			tb.trace.getMemoryManager()
					.addRegion("bin:.text", Range.atLeast(0L), tb.range(0x00300000, 0x00500000),
						TraceMemoryFlag.READ, TraceMemoryFlag.EXECUTE);
		}
		addRegisterValues(thread);
		addRegisterTypes(thread);
		// Ensure cause is goto PC, not register tracking
		listingPlugin.setTrackingSpec(NoneLocationTrackingSpec.INSTANCE);
		activateThread(thread);
		waitForSwing();

		assertPCRowTypePopulated();
		assertR0RowTypePopulated();

		// Verify nothing else has caused address to be PC
		assertEquals(tb.addr(0x00300000), listingPlugin.getCurrentAddress());

		RegisterRow row = findRegisterRow(pc);
		int rowNo = registersProvider.regsTableModel.getRowIndex(row);
		int colNo = RegisterTableColumns.REPR.ordinal();
		clickTableCell(registersProvider.regsTable, rowNo, colNo, 2);
		waitForSwing();

		assertEquals(tb.addr(0x00400000), listingPlugin.getCurrentAddress());
	}

	@Test
	public void testActionSelectRegisters() throws Exception {
		// TODO: Differences in behavior with live target?
		traceManager.openTrace(tb.trace);
		TraceThread thread = addThread();
		waitForSwing();

		assertFalse(registersProvider.actionSelectRegisters.isEnabled());

		activateThread(thread);
		waitForSwing();

		assertTrue(registersProvider.regsTableModel.getRowIndex(findRegisterRow(pc)) >= 0);
		assertTrue(registersProvider.actionSelectRegisters.isEnabled());

		performAction(registersProvider.actionSelectRegisters, false);
		DebuggerAvailableRegistersDialog dialog =
			waitForDialogComponent(DebuggerAvailableRegistersDialog.class);

		List<AvailableRegisterRow> modelData = dialog.availableTableModel.getModelData();
		assertEquals(52, modelData.size());
		AvailableRegisterRow pcAvail =
			modelData.stream().filter(r -> r.getRegister() == pc).findFirst().orElse(null);
		assertNotNull(pcAvail);

		runSwing(() -> {
			pcAvail.setSelected(false);
			dialog.availableTableModel.fireTableDataChanged();
			dialog.okCallback();
		});

		assertNull(getRegisterRow(pc));
		assertTrue(registersProvider.actionSelectRegisters.isEnabled());

		traceManager.closeTrace(tb.trace);
		waitForSwing();

		assertFalse(registersProvider.actionSelectRegisters.isEnabled());
	}

	// TODO: Default register selection with active recording

	@Test
	public void testTraceThreadActivation() throws Exception {
		traceManager.openTrace(tb.trace);
		TraceThread thread1 = addThread();
		TraceThread thread2 = addThread("Thread2");
		addRegisterValues(thread1);
		activateThread(thread2);
		waitForSwing();

		assertEquals(thread2, registersProvider.current.getThread());
		assertPCRowValueEmpty();

		// Should have no effect
		activateThread(thread2);
		waitForSwing();

		assertPCRowValueEmpty();
		assertEquals(thread2, registersProvider.current.getThread());

		// Should have effect
		activateThread(thread1);
		waitForSwing();

		assertEquals(thread1, registersProvider.current.getThread());
		assertPCRowValuePopulated();

		// Should have no effect
		traceManager.activateTrace(tb.trace);
		waitForSwing();

		assertEquals(thread1, registersProvider.current.getThread());
		assertPCRowValuePopulated();

		try (ToyDBTraceBuilder ub =
			new ToyDBTraceBuilder("dynamic2-" + name.getMethodName(), LANGID_TOYBE64)) {

			traceManager.openTrace(ub.trace);

			TraceThread thread3;
			try (UndoableTransaction tid = ub.startTransaction()) {
				thread3 = ub.trace.getThreadManager().createThread("Thread3", 0);
			}
			traceManager.activateTrace(ub.trace);
			waitForDomainObject(ub.trace);

			// NB: Since it's a dead trace, manager will select eldest thread
			assertEquals(thread3, registersProvider.current.getThread());
			assertPCRowValueEmpty();

			// Should just work
			activateThread(thread1);
			waitForSwing();

			assertEquals(thread1, registersProvider.current.getThread());
			assertPCRowValuePopulated();
		}
	}

	@Test
	public void testSnapActivation() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		addRegisterValues(thread);
		addRegisterTypes(thread);
		waitForDomainObject(tb.trace);

		assertR0RowValuePopulated();
		assertR0RowTypePopulated();

		try (UndoableTransaction tid = tb.startTransaction()) {
			TraceMemorySpace regVals =
				tb.trace.getMemoryManager().getMemoryRegisterSpace(thread, true);
			TraceCodeSpace regCode =
				tb.trace.getCodeManager().getCodeRegisterSpace(thread, true);
			regVals.putBytes(getPlatform(), 10, r0, tb.buf(0, 0, 0, 0, 0, 0, 0, 0));
			// NB. the manager should have split the data unit at the value change
			TraceCodeUnit cu = regCode.codeUnits().getContaining(getPlatform(), 10, r0);
			assertNotNull(cu);
			assertEquals(10, cu.getStartSnap());
			cu.delete();
		}
		waitForDomainObject(tb.trace);

		traceManager.activateSnap(5);
		waitForSwing();

		assertR0RowValuePopulated();
		assertR0RowTypePopulated();

		traceManager.activateSnap(15);
		waitForSwing();

		assertR0RowValueEmpty();
		assertR0RowTypeEmpty();
	}

	@Test
	public void testFrameActivation() throws Exception {
		traceManager.openTrace(tb.trace);

		TraceThread thread = addThread();
		activateThread(thread);
		waitForSwing();

		addRegisterValues(thread);
		addRegisterTypes(thread);
		waitForDomainObject(tb.trace);

		assertR0RowValuePopulated();
		assertR0RowTypePopulated();

		try (UndoableTransaction tid = tb.startTransaction()) {
			TraceMemorySpace regVals =
				tb.trace.getMemoryManager().getMemoryRegisterSpace(thread, 1, true);
			regVals.putBytes(getPlatform(), 0, pc, tb.buf(0, 0, 0, 0, 0, 0x50, 0, 0));

			TraceCodeSpace regCode =
				tb.trace.getCodeManager().getCodeRegisterSpace(thread, 1, true);
			regCode.definedData()
					.create(getPlatform(), Range.atLeast(0L), pc, QWordDataType.dataType);
		}
		waitForDomainObject(tb.trace);

		assertR0RowValuePopulated();
		assertR0RowTypePopulated();

		traceManager.activateFrame(1);
		waitForSwing();

		RegisterRow row = findRegisterRow(pc);
		assertEquals(0x00500000, row.getValue().longValue());
		assertTypeEquals(QWordDataType.dataType, row.getDataType());
	}

	// TODO: Colorizing changes (in particular with threads of different traces)
}
