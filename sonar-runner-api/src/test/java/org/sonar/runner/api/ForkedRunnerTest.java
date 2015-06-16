/*
 * SonarQube Runner - API
 * Copyright (C) 2011 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.runner.api;

import org.sonar.home.log.LogListener.Level;
import org.sonar.home.log.LogListener;
import org.sonar.runner.impl.Logs;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatcher;
import org.sonar.runner.impl.JarExtractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ForkedRunnerTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private ProcessMonitor processMonitor;

  private final StreamConsumer out = mock(StreamConsumer.class);

  private final StreamConsumer err = mock(StreamConsumer.class);

  @Before
  public void prepare() {
    processMonitor = mock(ProcessMonitor.class);
  }

  @Test
  public void should_create_forked_runner() {
    ForkedRunner runner = ForkedRunner.create();
    assertThat(runner).isNotNull().isInstanceOf(ForkedRunner.class);
  }

  @Test
  public void should_create_forked_runner_with_activity_controller() {
    ForkedRunner runner = ForkedRunner.create(processMonitor);
    assertThat(runner).isNotNull().isInstanceOf(ForkedRunner.class);
  }

  @Test
  public void should_use_log_listener() throws IOException {
    JarExtractor jarExtractor = createMockExtractor();

    CommandExecutor commandExecutor = mock(CommandExecutor.class);
    ForkedRunner runner = new ForkedRunner(jarExtractor, commandExecutor);
    runner.execute();
    LogListener listener = mock(LogListener.class);
    Logs.setListener(listener);

    ArgumentCaptor<StreamConsumer> arg1 = ArgumentCaptor.forClass(StreamConsumer.class);
    ArgumentCaptor<StreamConsumer> arg2 = ArgumentCaptor.forClass(StreamConsumer.class);
    
    verify(commandExecutor).execute(any(Command.class), arg1.capture(), arg2.capture(), anyLong(), any(ProcessMonitor.class));
    arg1.getValue().consumeLine("test1");
    arg2.getValue().consumeLine("test2");
    
    verify(listener).log("test1", Level.INFO);
    verify(listener).log("test2", Level.ERROR);
    verifyNoMoreInteractions(listener);
  }
  
  @Test
  public void should_print_to_consumers_by_default() throws IOException {
    final List<String> printedLines = new LinkedList<>();
    StreamConsumer consumer = new StreamConsumer() {
      @Override
      public void consumeLine(String line) {
        printedLines.add(line);
      }
    };
    JarExtractor jarExtractor = createMockExtractor();

    CommandExecutor commandExecutor = mock(CommandExecutor.class);
    ForkedRunner runner = new ForkedRunner(jarExtractor, commandExecutor);
    runner.setStdOut(consumer);
    runner.setStdErr(consumer);
    runner.execute();

    verify(commandExecutor).execute(any(Command.class), eq(consumer), eq(consumer), anyLong(),
        any(ProcessMonitor.class));
  }

  @Test
  public void properties_should_be_written_in_temp_file() throws Exception {
    JarExtractor jarExtractor = createMockExtractor();

    ForkedRunner runner = new ForkedRunner(jarExtractor, mock(CommandExecutor.class), any(ProcessMonitor.class));
    runner.setGlobalProperty("sonar.dynamicAnalysis", "false");
    runner.setGlobalProperty("sonar.login", "admin");
    runner.addJvmArguments("-Xmx512m");
    runner.addJvmEnvVariables(System.getenv());
    runner.setJvmEnvVariable("SONAR_HOME", "/path/to/sonar");

    ForkedRunner.ForkCommand forkCommand = runner.createCommand(runner.globalProperties());

    Properties properties = new Properties();
    properties.load(new FileInputStream(forkCommand.propertiesFile));
    assertThat(properties.size()).isEqualTo(2);
    assertThat(properties.getProperty("sonar.dynamicAnalysis")).isEqualTo("false");
    assertThat(properties.getProperty("sonar.login")).isEqualTo("admin");
    assertThat(properties.getProperty("-Xmx512m")).isNull();
    assertThat(properties.getProperty("SONAR_HOME")).isNull();
  }
  
  @Test
  public void should_merge_properties() throws IOException {
    JarExtractor jarExtractor = createMockExtractor();
    ForkedRunner runner = new ForkedRunner(jarExtractor, mock(CommandExecutor.class), null);

    ForkedRunner spy = Mockito.spy(runner);
    spy.setGlobalProperty("sonar.login", "admin");
    spy.execute();
    // generated analysis properties should have been added
    ArgumentCaptor<Properties> properties = ArgumentCaptor.forClass(Properties.class);
    verify(spy).writeProperties(properties.capture());
    assertThat(properties.getValue().keySet()).contains("sonar.working.directory", "sonar.host.url", "sonar.sourceEncoding", "sonar.login");
  }
  
  @Test
  public void test_java_command() throws IOException {
    JarExtractor jarExtractor = mock(JarExtractor.class);
    final File jar = temp.newFile();
    when(jarExtractor.extractToTemp("sonar-runner-impl")).thenReturn(jar);

    CommandExecutor commandExecutor = mock(CommandExecutor.class);

    ForkedRunner runner = new ForkedRunner(jarExtractor, commandExecutor);
    runner.setJavaExecutable("java");
    runner.setGlobalProperty("sonar.dynamicAnalysis", "false");
    runner.setGlobalProperty("sonar.login", "admin");
    runner.addJvmArguments("-Xmx512m");
    runner.addJvmEnvVariables(System.getenv());
    runner.setJvmEnvVariable("SONAR_HOME", "/path/to/sonar");
    runner.setStdOut(mock(StreamConsumer.class));
    runner.setStdErr(mock(StreamConsumer.class));

    assertThat(runner.jvmArguments()).contains("-Xmx512m");
    runner.execute();

    verify(commandExecutor).execute(argThat(new ArgumentMatcher<Command>() {
      public boolean matches(Object o) {
        Command command = (Command) o;
        assertThat(command.toStrings()).hasSize(6);
        assertThat(command.toStrings()[0]).isEqualTo("java");
        assertThat(command.toStrings()[1]).isEqualTo("-Xmx512m");
        assertThat(command.toStrings()[2]).isEqualTo("-cp");
        assertThat(command.toStrings()[3]).isEqualTo(jar.getAbsolutePath());
        assertThat(command.toStrings()[4]).isEqualTo("org.sonar.runner.impl.BatchLauncherMain");
        assertThat(command.toStrings()[5]).endsWith(".properties");

        // env variables
        assertThat(command.envVariables().size()).isGreaterThan(1);
        assertThat(command.envVariables().get("SONAR_HOME")).isEqualTo("/path/to/sonar");
        return true;
      }
    }), any(PrintStreamConsumer.class), any(PrintStreamConsumer.class), anyLong(), any(ProcessMonitor.class));

  }

  @Test
  public void test_failure_of_java_command() throws IOException {
    JarExtractor jarExtractor = createMockExtractor();
    CommandExecutor commandExecutor = createMockRunnerWithExecutionStatus(3);

    ForkedRunner runner = new ForkedRunner(jarExtractor, commandExecutor, processMonitor);
    runner.setStdOut(out);
    runner.setStdErr(err);

    try {
      runner.execute();
      fail();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).matches("Error status \\[command: .*java.*\\]: 3");
    }
  }

  private CommandExecutor createMockRunnerWithExecutionStatus(int executionStatus) {
    CommandExecutor commandExecutor = mock(CommandExecutor.class);
    when(commandExecutor.execute(any(Command.class), eq(out), eq(err), anyLong(), eq(processMonitor))).thenReturn(executionStatus);
    return commandExecutor;
  }

  private JarExtractor createMockExtractor() throws IOException {
    JarExtractor jarExtractor = mock(JarExtractor.class);
    final File jar = temp.newFile();
    when(jarExtractor.extractToTemp("sonar-runner-impl")).thenReturn(jar);
    return jarExtractor;
  }

  @Test
  public void test_runner_was_requested_to_stop() throws Exception {

    when(processMonitor.stop()).thenReturn(true);
    ForkedRunner runner = new ForkedRunner(createMockExtractor(), createMockRunnerWithExecutionStatus(143), processMonitor);
    runner.setStdOut(out);
    runner.setStdErr(err);
    runner.execute();
    verify(out).consumeLine("SonarQube Runner was stopped [status=143]");
  }
}
