/*
 * Copyright (c) 2013-2016, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2016, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor

import java.nio.file.Paths

import nextflow.Session
import nextflow.util.MemoryUnit
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TaskBeanTest extends Specification {

    def 'should create a bean object' () {

        given:

        def session = Mock(Session)
        session.getStatsEnabled() >> true
        session.getWorkDir() >> Paths.get('/work/dir')
        session.getBinDir() >> Paths.get('/bin/dir')

        def process = Mock(TaskProcessor)
        process.getConfig() >> ([unstageStrategy: 'rsync'] as ProcessConfig)
        process.getProcessEnvironment() >> [alpha: 'one', beta: 'two']
        process.getSession() >> session

        def config = new TaskConfig()
        config.module = ['blast/1.1']
        config.shell = ['bash', '-x']
        config.beforeScript = 'before do this'
        config.afterScript = 'after do that'
        config.memory = '1GB'

        def task = Mock(TaskRun)
        task.getId() >> '123'
        task.getName() >> 'Hello'
        task.getStdin() >> 'input from stdin'
        task.getScratch() >> '/tmp/x'
        task.getWorkDir() >> Paths.get('/work/dir')
        task.getTargetDir() >> Paths.get('/target/dir')
        task.getScript() >>  'echo Ciao mondo'

        task.getConfig() >> config
        task.getProcessor() >> process
        task.getInputFilesMap() >> [file_1: Paths.get('/file/one'), file_2: Paths.get('/file/two')]
        task.getOutputFilesNames() >> [ 'simple.txt', 'my/path/file.bam' ]
        task.getTargetDir() >> Paths.get('/target/work/dir')
        task.getInputEnvironment() >> [beta: 'xxx', gamma: 'yyy']
        task.getContainer() >> 'busybox:latest'
        task.getDockerConfig() >> [docker: true, registry: 'x']
        task.isContainerExecutable() >> true

        when:
        def bean = new TaskBean(task)

        then:
        bean.name == 'Hello'
        bean.input == 'input from stdin'
        bean.scratch == '/tmp/x'
        bean.workDir == Paths.get('/work/dir')
        bean.targetDir == Paths.get('/target/dir')

        bean.environment == [alpha: 'one', beta:'xxx', gamma: 'yyy']
        bean.moduleNames ==  ['blast/1.1']
        bean.shell ==  ['bash', '-x']
        bean.script == 'echo Ciao mondo'
        bean.beforeScript == 'before do this'
        bean.afterScript == 'after do that'

        bean.containerImage == 'busybox:latest'
        bean.containerConfig == [docker: true, registry: 'x']
        bean.containerMemory == new MemoryUnit('1GB')
        bean.executable
        bean.statsEnabled

        bean.inputFiles == [file_1: Paths.get('/file/one'), file_2: Paths.get('/file/two')]
        bean.outputFiles ==  [ 'simple.txt', 'my/path/file.bam' ]
        bean.workDir == Paths.get('/work/dir')
        bean.binDir == Paths.get('/bin/dir')
        bean.unstageStrategy == 'rsync'

    }

    def 'should clone task bean' () {

        given:
        def task = new TaskBean(
                name: 'Hello',
                environment: [A: 'Alpha', B: 'Beta'],
                moduleNames: ['x','y'],
                workDir: Paths.get('/a/b'),
                containerMemory: new MemoryUnit('2GB')
        )

        when:
        def copy = task.clone()

        then:
        copy.name == task.name
        copy.environment == task.environment
        copy.moduleNames == task.moduleNames
        copy.workDir == task.workDir
        copy.containerMemory == task.containerMemory
    }
}
