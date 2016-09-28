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

package nextflow.executor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import nextflow.Session
import nextflow.processor.TaskBean
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import spock.lang.Specification
import test.TestHelper
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class BashWrapperBuilderTest extends Specification {

    def 'test change to scratchDir' () {

        setup:
        def builder = new BashWrapperBuilder(new TaskBean())

        expect:
        builder.changeToScratchDirectory() == null

        when:
        builder.scratch = true
        then:
        builder.changeToScratchDirectory() == 'NXF_SCRATCH="$(set +u; nxf_mktemp $TMPDIR)" && cd $NXF_SCRATCH'

        when:
        builder.scratch = '$SOME_DIR'
        then:
        builder.changeToScratchDirectory() == 'NXF_SCRATCH="$(set +u; nxf_mktemp $SOME_DIR)" && cd $NXF_SCRATCH'

        when:
        builder.scratch = '/my/temp'
        then:
        builder.changeToScratchDirectory() == 'NXF_SCRATCH="$(set +u; nxf_mktemp /my/temp)" && cd $NXF_SCRATCH'

        when:
        builder.scratch = 'ram-disk'
        then:
        builder.changeToScratchDirectory() == 'NXF_SCRATCH="$(nxf_mktemp /dev/shm/)" && cd $NXF_SCRATCH'

    }


    def 'test map constructor'() {

        given:
        def bean = new TaskBean(
                input: 'alpha',
                scratch: '$var_x',
                workDir: Paths.get('a'),
                targetDir: Paths.get('b'),
                containerImage: 'docker_x',
                environment: [a:1, b:2],
                script: 'echo ciao',
                shell: ['bash','-e']
        )

        when:
        def wrapper = new BashWrapperBuilder(bean)

        then:
        wrapper.scratch == '$var_x'
        wrapper.input == 'alpha'
        wrapper.workDir == Paths.get('a')
        wrapper.targetDir == Paths.get('b')
        wrapper.containerImage == 'docker_x'
        wrapper.environment ==  [a:1, b:2]
        wrapper.script ==  'echo ciao'
        wrapper.shell == ['bash','-e']
    }


    def 'test bash wrapper' () {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * simple bash run
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 1',
                workDir: folder,
                script: 'echo Hello world!',
                headerScript: '#BSUB -x 1\n#BSUB -y 2'
            ] as TaskBean )
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                #BSUB -x 1
                #BSUB -y 2
                # NEXTFLOW TASK: Hello 1
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin
                [ -f ${folder}/.command.env ] && source ${folder}/.command.env

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                /bin/bash -ue ${folder}/.command.sh
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                """
                .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }


    def 'test bash wrapper with trace'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * simple bash run
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 1',
                workDir: folder,
                script: 'echo Hello world!',
                statsEnabled: true] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))
        Files.exists(folder.resolve('.command.run.1'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 1
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin
                [ -f ${folder}/.command.env ] && source ${folder}/.command.env

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                /bin/bash ${folder}/.command.run.1
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                """
                        .stripIndent().leftTrim()

                folder.resolve('.command.run.1').text ==
                """
                #!/bin/bash
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 3 ]] && set -x

                nxf_tree() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    stat() {
                        local x_ps=\$(ps -o pid=,state=,pcpu=,pmem=,vsz=,rss= \$1)
                        local x_io=\$(cat /proc/\$1/io 2> /dev/null | sed 's/^.*:\\s*//' | tr '\\n' ' ')
                        local x_vm=\$(cat /proc/\$1/status 2> /dev/null | egrep 'VmPeak|VmHWM' | sed 's/^.*:\\s*//' | sed 's/[\\sa-zA-Z]*\$//' | tr '\\n' ' ')
                        [[ ! \$x_ps ]] && return 0

                        printf "\$x_ps"
                        if [[ \$x_vm ]]; then printf " \$x_vm"; else printf " 0 0"; fi
                        if [[ \$x_io ]]; then printf " \$x_io"; fi
                        printf "\\n"
                    }

                    walk() {
                        stat \$1
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                nxf_pstat() {
                    local data=\$(nxf_tree \$1)
                    local tot=''
                    if [[ "\$data" ]]; then
                      tot=\$(awk '{ t3+=(\$3*10); t4+=(\$4*10); t5+=\$5; t6+=\$6; t7+=\$7; t8+=\$8; t9+=\$9; t10+=\$10; t11+=\$11; t12+=\$12; t13+=\$13; t14+=\$14 } END { printf "%d 0 %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f\\n", NR,t3,t4,t5,t6,t7,t8,t9,t10,t11,t12,t13,t14 }' <<< "\$data")
                      printf "\$tot\\n"
                    fi
                }

                nxf_sleep() {
                  if [[ \$1 < 0 ]]; then sleep 5;
                  elif [[ \$1 < 10 ]]; then sleep 0.1;
                  elif [[ \$1 < 130 ]]; then sleep 1;
                  else sleep 5; fi
                }

                nxf_date() {
                    case `uname` in
                        Darwin) if hash gdate 2>/dev/null; then echo 'gdate +%s%3N'; else echo 'date +%s000'; fi;;
                        *) echo 'date +%s%3N';;
                    esac
                }

                NXF_DATE=\$(nxf_date)

                nxf_trace() {
                  local pid=\$1; local trg=\$2;
                  local tot;
                  local count=0;
                  declare -a max=(); for i in {0..13}; do max[i]=0; done
                  while [[ true ]]; do
                    tot=\$(nxf_pstat \$pid)
                    [[ ! \$tot ]] && break
                    IFS=' ' read -a val <<< "\$tot"; unset IFS
                    for i in {0..13}; do
                      [ \${val[i]} -gt \${max[i]} ] && max[i]=\${val[i]}
                    done
                    echo "pid state %cpu %mem vmem rss peak_vmem peak_rss rchar wchar syscr syscw read_bytes write_bytes" > \$trg
                    echo "\${max[@]}" >> \$trg
                    nxf_sleep \$count
                    count=\$((count+1))
                  done
                }


                trap 'exit \${ret:=\$?}' EXIT
                touch .command.trace
                start_millis=\$(\$NXF_DATE)
                (
                /bin/bash -ue ${folder}/.command.sh
                ) &
                pid=\$!
                nxf_trace "\$pid" .command.trace &
                mon=\$!
                wait \$pid || ret=\$?
                end_millis=\$(\$NXF_DATE)
                kill \$mon || wait \$mon
                echo \$((end_millis-start_millis)) >> .command.trace
                """
                    .stripIndent().leftTrim()

        cleanup:
        folder?.deleteDir()
    }

    def 'test bash wrapper with scratch and input'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * simple bash run
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 1',
                workDir: folder,
                script: 'echo Hello world!',
                scratch: true,
                input: 'Ciao ciao'
            ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))
        Files.exists(folder.resolve('.command.in'))

        folder.resolve('.command.in').text == 'Ciao ciao'

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 1
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin
                [ -f ${folder}/.command.env ] && source ${folder}/.command.env
                NXF_SCRATCH="\$(set +u; nxf_mktemp \$TMPDIR)" && cd \$NXF_SCRATCH

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                /bin/bash -ue ${folder}/.command.sh < ${folder}/.command.in
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                cp .command.out ${folder} || true
                cp .command.err ${folder} || true
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }

    def 'test bash wrapper with scratch and input and stats'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * simple bash run
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 2',
                workDir: folder,
                script: 'echo Hello world!',
                scratch: true,
                input: 'data xyz',
                statsEnabled: true
                ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))
        Files.exists(folder.resolve('.command.in'))
        Files.exists(folder.resolve('.command.run.1'))

        /*
         * data input file
         */
        folder.resolve('.command.in').text == 'data xyz'

        /*
         * the user script  file
         */
        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        /*
         * the main script launcher
         */
        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 2
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin
                [ -f ${folder}/.command.env ] && source ${folder}/.command.env
                NXF_SCRATCH="\$(set +u; nxf_mktemp \$TMPDIR)" && cd \$NXF_SCRATCH

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                /bin/bash ${folder}/.command.run.1
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                cp .command.out ${folder} || true
                cp .command.err ${folder} || true
                cp .command.trace ${folder} || true
                """
                        .stripIndent().leftTrim()

        folder.resolve('.command.run.1').text ==
            """
            #!/bin/bash
            set -e
            set -u
            NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 3 ]] && set -x

            nxf_tree() {
                declare -a ALL_CHILD
                while read P PP;do
                    ALL_CHILD[\$PP]+=" \$P"
                done < <(ps -e -o pid= -o ppid=)

                stat() {
                    local x_ps=\$(ps -o pid=,state=,pcpu=,pmem=,vsz=,rss= \$1)
                    local x_io=\$(cat /proc/\$1/io 2> /dev/null | sed 's/^.*:\\s*//' | tr '\\n' ' ')
                    local x_vm=\$(cat /proc/\$1/status 2> /dev/null | egrep 'VmPeak|VmHWM' | sed 's/^.*:\\s*//' | sed 's/[\\sa-zA-Z]*\$//' | tr '\\n' ' ')
                    [[ ! \$x_ps ]] && return 0

                    printf "\$x_ps"
                    if [[ \$x_vm ]]; then printf " \$x_vm"; else printf " 0 0"; fi
                    if [[ \$x_io ]]; then printf " \$x_io"; fi
                    printf "\\n"
                }

                walk() {
                    stat \$1
                    for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                }

                walk \$1
            }

            nxf_pstat() {
                local data=\$(nxf_tree \$1)
                local tot=''
                if [[ "\$data" ]]; then
                  tot=\$(awk '{ t3+=(\$3*10); t4+=(\$4*10); t5+=\$5; t6+=\$6; t7+=\$7; t8+=\$8; t9+=\$9; t10+=\$10; t11+=\$11; t12+=\$12; t13+=\$13; t14+=\$14 } END { printf "%d 0 %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f\\n", NR,t3,t4,t5,t6,t7,t8,t9,t10,t11,t12,t13,t14 }' <<< "\$data")
                  printf "\$tot\\n"
                fi
            }

            nxf_sleep() {
              if [[ \$1 < 0 ]]; then sleep 5;
              elif [[ \$1 < 10 ]]; then sleep 0.1;
              elif [[ \$1 < 130 ]]; then sleep 1;
              else sleep 5; fi
            }

            nxf_date() {
                case `uname` in
                    Darwin) if hash gdate 2>/dev/null; then echo 'gdate +%s%3N'; else echo 'date +%s000'; fi;;
                    *) echo 'date +%s%3N';;
                esac
            }

            NXF_DATE=\$(nxf_date)

            nxf_trace() {
              local pid=\$1; local trg=\$2;
              local tot;
              local count=0;
              declare -a max=(); for i in {0..13}; do max[i]=0; done
              while [[ true ]]; do
                tot=\$(nxf_pstat \$pid)
                [[ ! \$tot ]] && break
                IFS=' ' read -a val <<< "\$tot"; unset IFS
                for i in {0..13}; do
                  [ \${val[i]} -gt \${max[i]} ] && max[i]=\${val[i]}
                done
                echo "pid state %cpu %mem vmem rss peak_vmem peak_rss rchar wchar syscr syscw read_bytes write_bytes" > \$trg
                echo "\${max[@]}" >> \$trg
                nxf_sleep \$count
                count=\$((count+1))
              done
            }


            trap 'exit \${ret:=\$?}' EXIT
            touch .command.trace
            start_millis=\$(\$NXF_DATE)
            (
            /bin/bash -ue ${folder}/.command.sh < ${folder}/.command.in
            ) &
            pid=\$!
            nxf_trace "\$pid" .command.trace &
            mon=\$!
            wait \$pid || ret=\$?
            end_millis=\$(\$NXF_DATE)
            kill \$mon || wait \$mon
            echo \$((end_millis-start_millis)) >> .command.trace
            """
                    .stripIndent().leftTrim()

        cleanup:
        folder?.deleteDir()
    }

    /**
     * test running with Docker executed as 'sudo'
     */
    def 'test bash wrapper with docker' () {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 3',
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'busybox',
                containerConfig: [sudo: true, enabled: true]
                ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 3
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    sudo docker kill \$NXF_BOXID
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                sudo docker run -i -v ${folder}:${folder} -v "\$PWD":"\$PWD" -w "\$PWD" --entrypoint /bin/bash --name \$NXF_BOXID busybox -c "/bin/bash -ue ${folder}/.command.sh"
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                sudo docker rm \$NXF_BOXID &>/dev/null || true
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }

    def 'test bash wrapper with docker 2'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 4',
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'busybox',
                containerConfig: [temp: 'auto', enabled: true]
                ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 4
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    docker kill \$NXF_BOXID
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                docker run -i -v \$(nxf_mktemp):/tmp -v ${folder}:${folder} -v "\$PWD":"\$PWD" -w "\$PWD" --entrypoint /bin/bash --name \$NXF_BOXID busybox -c "/bin/bash -ue ${folder}/.command.sh"
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                docker rm \$NXF_BOXID &>/dev/null || true
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }

    /**
     * Test run in a docker container, without removing it
     */
    def 'test bash wrapper with docker 3'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 5',
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'ubuntu',
                containerConfig: [temp: 'auto', enabled: true, remove:false, kill: false]
                ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 5
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                docker run -i -v \$(nxf_mktemp):/tmp -v ${folder}:${folder} -v "\$PWD":"\$PWD" -w "\$PWD" --entrypoint /bin/bash --name \$NXF_BOXID ubuntu -c "/bin/bash -ue ${folder}/.command.sh"
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }

    def 'test bash wrapper with docker 4'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 6',
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'ubuntu',
                containerConfig: [temp: 'auto', enabled: true, remove:false, kill: 'SIGXXX']
                ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 6
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    docker kill -s SIGXXX \$NXF_BOXID
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                docker run -i -v \$(nxf_mktemp):/tmp -v ${folder}:${folder} -v "\$PWD":"\$PWD" -w "\$PWD" --entrypoint /bin/bash --name \$NXF_BOXID ubuntu -c "/bin/bash -ue ${folder}/.command.sh"
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }

    /**
     * test running with Docker executed as 'sudo'
     */
    def 'test bash wrapper with docker mount' () {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 7',
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'busybox',
                containerMount: '/folder with blanks' as Path,
                containerConfig: [enabled: true]
        ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 7
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    docker kill \$NXF_BOXID
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                docker run -i -v ${folder}:${folder} -v /folder\\ with\\ blanks:/folder\\ with\\ blanks -v "\$PWD":"\$PWD" -w "\$PWD" --entrypoint /bin/bash --name \$NXF_BOXID busybox -c "/bin/bash -ue ${folder}/.command.sh"
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                docker rm \$NXF_BOXID &>/dev/null || true
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }

    def 'should append chown command to fix ownership of files created by docker' () {

        given:
        def folder = TestHelper.createInMemTempDir()

        /*
          * bash run through docker
          */
        when:
        def bash = new BashWrapperBuilder([
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'sl65',
                containerConfig: [enabled: true, fixOwnership: true]
                ] as TaskBean)
        bash.systemOsName = 'Linux'
        bash.build()

        then:

        folder.resolve('.command.sh').text ==
                """
                #!/bin/bash -ue
                echo Hello world!

                # patch root ownership problem of files created with docker
                [ \${NXF_OWNER:=''} ] && chown -fR --from root \$NXF_OWNER ${folder}/{*,.*} || true
                """
                        .stripIndent().leftTrim()

    }

    def 'should create script for docker executable container' () {
        given:
        def folder = TestHelper.createInMemTempDir()
        def session = new Session(); session.workDir = folder
        def task = new TaskRun(
                name: 'Hello',
                script: 'FOO=bar\ndocker-io/busybox --fox --baz',
                config: [container: true],
                workDir: folder )
        task.processor = Mock(TaskProcessor)
        task.processor.getProcessEnvironment() >> [:]
        task.processor.getSession() >> session
        task.processor.getConfig() >> [:]

        when:
        def bash = new BashWrapperBuilder(task)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                """
                #!/bin/bash -ue
                FOO=bar
                docker run -i -e "NXF_DEBUG=\${NXF_DEBUG:=0}" -e "FOO=bar" -v $folder:$folder -v "\$PWD":"\$PWD" -w "\$PWD" --name \$NXF_BOXID docker-io/busybox --fox --baz
                """
                .stripIndent().leftTrim()

        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    docker kill \$NXF_BOXID
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                /bin/bash -ue ${folder}/.command.sh
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                docker rm \$NXF_BOXID &>/dev/null || true
                """
                        .stripIndent().leftTrim()
    }

    def 'should create docker run with registry' () {
        given:
        def folder = TestHelper.createInMemTempDir()
        def task = new TaskRun(
                name: 'Hello',
                script: 'my.registry.com/docker-io/busybox --fox --baz',
                config: [container: true],
                workDir: folder )
        task.processor = Mock(TaskProcessor)
        task.processor.getProcessEnvironment() >> [:]
        task.processor.getSession() >> new Session(docker: [registry: 'registry.com'])
        task.processor.getSession().workDir = folder
        task.processor.getConfig() >> [:]

        when:
        def bash = new BashWrapperBuilder(task)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                """
                #!/bin/bash -ue
                docker run -i -v $folder:$folder -v "\$PWD":"\$PWD" -w "\$PWD" --name \$NXF_BOXID my.registry.com/docker-io/busybox --fox --baz
                """
                        .stripIndent().leftTrim()
    }

    def 'test shell exit function' () {

        def bash

        when:
        bash = new BashWrapperBuilder( new TaskBean() )
        then:
        bash.scriptCleanUp( Paths.get("/my/exit/file's"), null ) ==
                    '''
                    nxf_env() {
                        echo '============= task environment ============='
                        env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                        echo '============= task output =================='
                    }

                    nxf_kill() {
                        declare -a ALL_CHILD
                        while read P PP;do
                            ALL_CHILD[$PP]+=" $P"
                        done < <(ps -e -o pid= -o ppid=)

                        walk() {
                            [[ $1 != $$ ]] && kill $1 2>/dev/null || true
                            for i in ${ALL_CHILD[$1]:=}; do walk $i; done
                        }

                        walk $1
                    }

                    function nxf_mktemp() {
                        local base=\${1:-/tmp}
                        if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                        else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                        fi
                    }

                    on_exit() {
                      exit_status=${ret:=$?}
                      printf $exit_status > /my/exit/file\\'s
                      set +u
                      [[ "\$COUT" ]] && rm -f "\$COUT" || true
                      [[ "\$CERR" ]] && rm -f "\$CERR" || true
                      exit $exit_status
                    }

                    on_term() {
                        set +e
                        [[ "$pid" ]] && nxf_kill $pid
                    }

                    trap on_exit EXIT
                    trap on_term TERM INT USR1 USR2
                    '''
                    .stripIndent().leftTrim()


        when:
        bash = new BashWrapperBuilder(new TaskBean())
        then:
        bash.scriptCleanUp( Paths.get('/my/exit/xxx'), 'docker stop x' ) ==
                '''
                    nxf_env() {
                        echo '============= task environment ============='
                        env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                        echo '============= task output =================='
                    }

                    nxf_kill() {
                        declare -a ALL_CHILD
                        while read P PP;do
                            ALL_CHILD[$PP]+=" $P"
                        done < <(ps -e -o pid= -o ppid=)

                        walk() {
                            [[ $1 != $$ ]] && kill $1 2>/dev/null || true
                            for i in ${ALL_CHILD[$1]:=}; do walk $i; done
                        }

                        walk $1
                    }

                    function nxf_mktemp() {
                        local base=\${1:-/tmp}
                        if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                        else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                        fi
                    }

                    on_exit() {
                      exit_status=${ret:=$?}
                      printf $exit_status > /my/exit/xxx
                      set +u
                      [[ "\$COUT" ]] && rm -f "\$COUT" || true
                      [[ "\$CERR" ]] && rm -f "\$CERR" || true
                      exit $exit_status
                    }

                    on_term() {
                        set +e
                        docker stop x
                    }

                    trap on_exit EXIT
                    trap on_term TERM INT USR1 USR2
                    '''
                        .stripIndent().leftTrim()

    }

    def 'test environment file' () {

        given:
        def folder

        when:
        folder = TestHelper.createInMemTempDir()
        new BashWrapperBuilder([ workDir: folder, environment: [ALPHA:1, GAMMA:2], script: 'Hello world' ] as TaskBean ) .build()

        then:
        folder.resolve('.command.env').text == '''
                    export ALPHA="1"
                    export GAMMA="2"
                    '''
                    .stripIndent().leftTrim()

        when:
        folder = TestHelper.createInMemTempDir()
        new BashWrapperBuilder([ workDir: folder, environment: [DELTA:1, OMEGA:2], script: 'Hello world', moduleNames: ['xx/1.2','yy/3.4'] ] as TaskBean ) .build()

        then:
        folder.resolve('.command.env').text == '''
                    nxf_module_load(){
                      local mod=$1
                      local ver=${2:-}
                      local new_module="$mod"; [[ $ver ]] && new_module+="/$ver"

                      if [[ ! $(module list 2>&1 | grep -o "$new_module") ]]; then
                        old_module=$(module list 2>&1 | grep -Eo "$mod\\/[^\\( \\n]+" || true)
                        if [[ $ver && $old_module ]]; then
                          module switch $old_module $new_module
                        else
                          module load $new_module
                        fi
                      fi
                    }

                    nxf_module_load xx 1.2
                    nxf_module_load yy 3.4
                    export DELTA="1"
                    export OMEGA="2"
                    '''
                .stripIndent().leftTrim()

        when:
        folder = TestHelper.createInMemTempDir()
        new BashWrapperBuilder([ workDir: folder, script: 'Hello world', moduleNames: ['ciao/1','mondo/2', 'bioinfo-tools'] ] as TaskBean) .build()

        then:
        folder.resolve('.command.env').text == '''
                    nxf_module_load(){
                      local mod=$1
                      local ver=${2:-}
                      local new_module="$mod"; [[ $ver ]] && new_module+="/$ver"

                      if [[ ! $(module list 2>&1 | grep -o "$new_module") ]]; then
                        old_module=$(module list 2>&1 | grep -Eo "$mod\\/[^\\( \\n]+" || true)
                        if [[ $ver && $old_module ]]; then
                          module switch $old_module $new_module
                        else
                          module load $new_module
                        fi
                      fi
                    }

                    nxf_module_load ciao 1
                    nxf_module_load mondo 2
                    nxf_module_load bioinfo-tools
                    '''
                .stripIndent().leftTrim()

    }


    def 'test before/after script' () {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 9',
                workDir: folder,
                script: 'echo Hello world!',
                beforeScript: "init this",
                afterScript: "cleanup that"
                ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 9
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin
                # user `beforeScript`
                init this
                [ -f ${folder}/.command.env ] && source ${folder}/.command.env

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                /bin/bash -ue ${folder}/.command.sh
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                # user `afterScript`
                cleanup that
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()


    }

    def 'test bash wrapper with shifter'() {

        given:
        def folder = Files.createTempDirectory('test')

        /*
         * bash run through docker
         */
        when:
        def bash = new BashWrapperBuilder([
                name: 'Hello 1',
                workDir: folder,
                script: 'echo Hello world!',
                containerImage: 'docker:ubuntu:latest',
                environment: [PATH: '/path/to/bin'],
                shifterConfig: [enabled: true],
                containerConfig: [:]
        ] as TaskBean)
        bash.build()

        then:
        Files.exists(folder.resolve('.command.sh'))
        Files.exists(folder.resolve('.command.run'))

        folder.resolve('.command.sh').text ==
                '''
                #!/bin/bash -ue
                echo Hello world!
                '''
                        .stripIndent().leftTrim()


        folder.resolve('.command.run').text ==
                """
                #!/bin/bash
                # NEXTFLOW TASK: Hello 1
                set -e
                set -u
                NXF_DEBUG=\${NXF_DEBUG:=0}; [[ \$NXF_DEBUG > 2 ]] && set -x


                function shifter_img() {
                  local cmd=\$1
                  local image=\$2
                  shifterimg -v \$cmd \$image |  awk -F: '\$0~/status/{gsub("[\\", ]","",\$2);print \$2}'
                }

                function shifter_pull() {
                  local image=\$1
                  local STATUS=\$(shifter_img lookup \$image)
                  if [[ \$STATUS != READY && \$STATUS != '' ]]; then
                    STATUS=\$(shifter_img pull \$image)
                    while [[ \$STATUS != READY && \$STATUS != FAILURE && \$STATUS != '' ]]; do
                      sleep 5
                      STATUS=\$(shifter_img pull \$image)
                    done
                  fi

                  [[ \$STATUS == FAILURE || \$STATUS == '' ]] && echo "Shifter failed to pull image \\`\$image\\`" >&2  && exit 1
                }

                nxf_env() {
                    echo '============= task environment ============='
                    env | sort | sed "s/\\(.*\\)AWS\\(.*\\)=\\(.\\{6\\}\\).*/\\1AWS\\2=\\3xxxxxxxxxxxxx/"
                    echo '============= task output =================='
                }

                nxf_kill() {
                    declare -a ALL_CHILD
                    while read P PP;do
                        ALL_CHILD[\$PP]+=" \$P"
                    done < <(ps -e -o pid= -o ppid=)

                    walk() {
                        [[ \$1 != \$\$ ]] && kill \$1 2>/dev/null || true
                        for i in \${ALL_CHILD[\$1]:=}; do walk \$i; done
                    }

                    walk \$1
                }

                function nxf_mktemp() {
                    local base=\${1:-/tmp}
                    if [[ \$(uname) = Darwin ]]; then mktemp -d \$base/nxf.XXXXXXXXXX
                    else TMPDIR="\$base" mktemp -d -t nxf.XXXXXXXXXX
                    fi
                }

                on_exit() {
                  exit_status=\${ret:=\$?}
                  printf \$exit_status > ${folder}/.exitcode
                  set +u
                  [[ "\$COUT" ]] && rm -f "\$COUT" || true
                  [[ "\$CERR" ]] && rm -f "\$CERR" || true
                  exit \$exit_status
                }

                on_term() {
                    set +e
                    [[ "\$pid" ]] && nxf_kill \$pid
                }

                trap on_exit EXIT
                trap on_term TERM INT USR1 USR2

                export NXF_BOXID="nxf-\$(dd bs=18 count=1 if=/dev/urandom 2>/dev/null | base64 | tr +/ 0A)"
                [[ \$NXF_DEBUG > 0 ]] && nxf_env
                touch ${folder}/.command.begin

                set +e
                COUT=\$PWD/.command.po; mkfifo "\$COUT"
                CERR=\$PWD/.command.pe; mkfifo "\$CERR"
                tee .command.out < "\$COUT" &
                tee1=\$!
                tee .command.err < "\$CERR" >&2 &
                tee2=\$!
                (
                shifter_pull docker:ubuntu:latest
                BASH_ENV=\"${folder}/.command.env\" shifter --image docker:ubuntu:latest /bin/bash -c "/bin/bash -ue ${folder}/.command.sh"
                ) >"\$COUT" 2>"\$CERR" &
                pid=\$!
                wait \$pid || ret=\$?
                wait \$tee1 \$tee2
                """
                        .stripIndent().leftTrim()


        cleanup:
        folder?.deleteDir()
    }


}
