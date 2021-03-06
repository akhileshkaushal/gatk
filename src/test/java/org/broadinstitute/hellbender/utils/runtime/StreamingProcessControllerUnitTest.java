package org.broadinstitute.hellbender.utils.runtime;

import org.broadinstitute.hellbender.testutils.BaseTest;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutorException;
import org.broadinstitute.hellbender.utils.python.StreamingPythonScriptExecutor;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

// Tests for the StreamingProcessController. The StreamingProcessController depends on the remote
// process cooperating via use of the ack fifo. Since the gatktool Python package implements the required
// cooperative methods, some of these tests depend on that package.
//
// NOTE: TestNG has a bug where it throws ArrayIndexOutOfBoundsException instead of TimeoutException
// exception when the test time exceeds the timeOut threshold. This is fixed but not yet released:
//
//  https://github.com/cbeust/testng/issues/1493
//
public class StreamingProcessControllerUnitTest extends BaseTest {
    private final static String NL = System.lineSeparator();

    @DataProvider(name="serialPythonCommands")
    private Object[][] getSerialPythonCommands() {
        return new Object[][] {
                { new String[]{ "x = 37", "x = x + 2"},
                        "str(x)",
                        "39" },
                { new String[]{ "bases = ['a', 'c', 'g', 't']", "bases.append('n')", "bases.sort()"},
                        "bases[3]",
                        "n" },
                { new String[]{
                        "stack = [1, 2, 3]", "stack.append(4)", "stack.append(5)", "stack.pop()", "stack.pop()", "stack.pop()", "stack.pop()",
                        "a = stack.pop()"},
                        "str(a)", "1" }
        };
    }

    @Test(dataProvider="serialPythonCommands", groups = "python", timeOut = 10000)
    public void testSerialCommands(
            final String[] serialPythonCommands,
            final String pythonExpression,
            final String expectedResultLine) throws IOException {
        final StreamingProcessController controller = initializePythonControllerWithAckFIFO(false);

        Arrays.stream(serialPythonCommands).forEach(command -> controller.writeProcessInput(command + NL));

        boolean ack = requestAndWaitForAck(controller);
        Assert.assertTrue(ack);

        final File tempFile = writePythonExpressionToTempFile(controller, pythonExpression);

        final String actualOutput = getLineFromTempFile(tempFile);
        Assert.assertEquals(actualOutput, expectedResultLine);

        terminatePythonController(controller);
        Assert.assertFalse(controller.getProcess().isAlive());
    }

    @Test(timeOut = 50000)
    public void testMultipleParallelStreamingControllers() throws IOException {
        final StreamingProcessController controller1 = initializePythonControllerWithAckFIFO(false);
        final StreamingProcessController controller2 = initializePythonControllerWithAckFIFO(false);

        controller1.writeProcessInput("x = 'process1'" + NL);
        controller2.writeProcessInput("x = 'process2'" + NL);

        final File tempFile1 = writePythonExpressionToTempFile(controller1, "str(x)");
        final File tempFile2 = writePythonExpressionToTempFile(controller2, "str(x)");

        Assert.assertEquals(getLineFromTempFile(tempFile1), "process1");
        Assert.assertEquals(getLineFromTempFile(tempFile2), "process2");

        controller1.terminate();
        controller2.terminate();

        Assert.assertFalse(controller1.getProcess().isAlive());
        Assert.assertFalse(controller2.getProcess().isAlive());
    }

    // The timeout value here needs to exceed the timeout value used by StreamingProcessController.
    @Test(groups = "python", timeOut = 60000)
    public void testStartupCommandExecution() throws IOException {
        final String writeOutTemplate = "fd=open('%s', 'w')\nfd.write('some output\\n')\nfd.close()\n";
        final File tempFile = createTempFile("streamingControllerStartupCommand", ".txt");
        final String writeOutScript = String.format(writeOutTemplate, tempFile.getAbsolutePath());

        final ProcessSettings processSettings = new ProcessSettings(new String[] {"python", "-i", "-u", "-c", writeOutScript});

        final StreamingProcessController controller = new StreamingProcessController(processSettings);

        Assert.assertNotNull(controller.start());
        controller.terminate();
        Assert.assertFalse(controller.getProcess().isAlive());

        Assert.assertEquals(getLineFromTempFile(tempFile), "some output");
    }

    @Test(groups = "python", timeOut = 10000)
    public void testStderrRedirect() {
        final StreamingProcessController controller = initializePythonControllerWithAckFIFO(true);

        // write to stderr, but we expect to get it from stdout due to redirection
        controller.writeProcessInput("import sys" + NL + "sys.stderr.write('error output to stderr\\n')" + NL);
        boolean ack = requestAndWaitForAck(controller);
        Assert.assertTrue(ack);

        ProcessOutput po = controller.getProcessOutput();

        Assert.assertNotNull(po.getStdout());
        Assert.assertNotNull(po.getStdout().getBufferString());
        Assert.assertNotNull(po.getStdout().getBufferString().contains("error output to stderr"));

        controller.terminate();
        Assert.assertFalse(controller.getProcess().isAlive());
    }

    @Test(timeOut = 10000)
    public void testFIFOLifetime() {
        // cat is a red herring here; we're just testing that a FIFO is created, and then deleted after termination
        final ProcessSettings catProcessSettings = new ProcessSettings(new String[] {"cat"});
        final StreamingProcessController catController = new StreamingProcessController(catProcessSettings);
        catController.start();

        final File fifo = catController.createDataFIFO();
        Assert.assertTrue(fifo.exists());

        catController.terminate();

        final File fifoParent = fifo.getParentFile();
        Assert.assertFalse(fifo.exists());
        Assert.assertFalse(fifoParent.exists());
        Assert.assertFalse(catController.getProcess().isAlive());
    }

    @Test(groups = "python", expectedExceptions = IllegalStateException.class)
    public void testRedundantStart() {
        final ProcessSettings processSettings = new ProcessSettings(new String[] {"python", "-i", "-u"});
        final StreamingProcessController catController = new StreamingProcessController(processSettings);
        Assert.assertNotNull(catController.start());

        try {
            catController.start();
        } finally {
            catController.terminate();
            Assert.assertFalse(catController.getProcess().isAlive());
        }
    }

    @DataProvider(name="nckMessages")
    private Object[][] getNckMessages() {
        return new Object[][] {
                // message, expected message length
                { "", "" },     // message of length 0
                { "1", "1" },   // message of length 1
                { "Test roundtrip negative ack with message protocol", "Test roundtrip negative ack with message protocol" },
                // one byte less than max
                { Utils.dupChar('s', StreamingToolConstants.STREAMING_NCK_WITH_MESSAGE_MAX_MESSAGE_LENGTH - 1),
                        Utils.dupChar('s', StreamingToolConstants.STREAMING_NCK_WITH_MESSAGE_MAX_MESSAGE_LENGTH - 1) },
                // exactly max bytes
                { Utils.dupChar('s', StreamingToolConstants.STREAMING_NCK_WITH_MESSAGE_MAX_MESSAGE_LENGTH),
                        Utils.dupChar('s', StreamingToolConstants.STREAMING_NCK_WITH_MESSAGE_MAX_MESSAGE_LENGTH) },  // 9999
                // max bytes + 1000, trimmed to max
                { Utils.dupChar('s', StreamingToolConstants.STREAMING_NCK_WITH_MESSAGE_MAX_MESSAGE_LENGTH + 1000),
                        Utils.dupChar('s', StreamingToolConstants.STREAMING_NCK_WITH_MESSAGE_MAX_MESSAGE_LENGTH) }, // > 9999 is trimmed to 9999
        };
    }

    @Test(groups = "python", dataProvider = "nckMessages", timeOut = 50000, expectedExceptions={PythonScriptExecutorException.class})
    public void testNckWithMessage(final String nkmMessage, final String expectedMessage) throws PythonScriptExecutorException {

        // Since testing the nack w/message StreamingProcessController functionality requires a cooperative remote
        // process that implements code to service the ack fifo, we test it indirectly via use of the
        // StreamingPythonExecutor, since that already depends on Python code that knows how to participate
        // in the protocol.
        final StreamingPythonScriptExecutor<String> streamingPythonExecutor =
                new StreamingPythonScriptExecutor<>(StreamingPythonScriptExecutor.PythonExecutableName.PYTHON3,true);
        Assert.assertNotNull(streamingPythonExecutor);
        Assert.assertTrue(streamingPythonExecutor.start(Collections.emptyList(), true, null));

        try {
            streamingPythonExecutor.sendSynchronousCommand(String.format("tool.sendNackWithMessage(\"%s\")" + NL, nkmMessage));
        }
        catch (PythonScriptExecutorException e) {
            Assert.assertTrue(e.getMessage().contains(expectedMessage));
            throw e;
        }
        finally {
            streamingPythonExecutor.terminate();
        }
    }

    private StreamingProcessController initializePythonControllerWithAckFIFO(boolean redirectStderr) {
        // start an interactive Python session with unbuffered IO
        final ProcessSettings processSettings = new ProcessSettings(new String[] {"python", "-i", "-u"});

        if (redirectStderr) {
            // redirect the process' stderr to stdout
            processSettings.setRedirectErrorStream(true);
        }

        final StreamingProcessController controller = new StreamingProcessController(processSettings, true);
        final File ackFIFO = controller.start();

        Assert.assertNotNull(ackFIFO);
        // manually do ack handshake
        controller.writeProcessInput(
                String.format(
                        "import os" + NL +
                                "ackFIFODescriptor = os.open('%s', os.O_WRONLY)" + NL +
                                "akcFIFOWriter = os.fdopen(ackFIFODescriptor, 'w')" + NL,
                        ackFIFO.getAbsolutePath())
        );

        controller.openAckFIFOForRead(); // unblock python
        return controller;
    }

    private File writePythonExpressionToTempFile(final StreamingProcessController controller, final String pythonExpression) {
        final File tempFile = createTempFile("testPythonStdoutSerial", "txt");
        controller.writeProcessInput(String.format("fd=open('%s', 'w')", tempFile.getAbsolutePath()) + NL);
        controller.writeProcessInput(String.format("fd.write(%s + '\\n')", pythonExpression) + NL);
        controller.writeProcessInput("fd.close()" + NL);
        final boolean ack = requestAndWaitForAck(controller);
        Assert.assertTrue(ack);
        return tempFile;
    }

    private boolean requestAndWaitForAck(final StreamingProcessController controller) {
        controller.writeProcessInput("akcFIFOWriter.write('ack')" + NL);
        controller.writeProcessInput("akcFIFOWriter.flush()" + NL);
        return controller.waitForAck().isPositiveAck();
    }

    private String getLineFromTempFile(final File tempFile) throws IOException {
        try (final FileReader fr = new FileReader(tempFile);
             final BufferedReader br = new BufferedReader(fr)) {
            return br.readLine();
        }
    }

    private void terminatePythonController(final StreamingProcessController controller) {
        controller.writeProcessInput("ackFIFOWriter.close()" + NL);
        controller.terminate();
    }

}
