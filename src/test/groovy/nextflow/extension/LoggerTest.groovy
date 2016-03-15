package nextflow.extension

import groovy.util.logging.Slf4j
import org.junit.Rule
import spock.lang.Specification
import test.OutputCapture

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class LoggerTest extends Specification {

    /*
     * Read more http://mrhaki.blogspot.com.es/2015/02/spocklight-capture-and-assert-system.html
     */
    @Rule
    OutputCapture capture = new OutputCapture()


    def 'should print only a debug line in a second'() {

        when:
        Dummy.log.debug1("Hello world", throttle: '1s')
        Dummy.log.debug1("Hello world", throttle: '1s')
        Dummy.log.debug1("Hello world", throttle: '1s')
        sleep (1_100)
        Dummy.log.debug1("Hello world", throttle: '1s')

        then:
        capture.toString().readLines().findAll { it.endsWith('Hello world') }.size() ==2
    }


    def 'should changed lines'() {

        when:
        Dummy.log.debug1("Hello world 1", throttle: '1s')
        Dummy.log.debug1("Hello world 2", throttle: '1s')
        Dummy.log.debug1("Hello world 1", throttle: '1s')
        Dummy.log.debug1("Hello world 2", throttle: '1s')

        then:
        capture.toString().readLines().findAll { it.contains('Hello world') }.size() ==2
        capture.toString().readLines().findAll { it.endsWith('Hello world 1') }.size() ==1
        capture.toString().readLines().findAll { it.endsWith('Hello world 2') }.size() ==1
    }

    def 'should print all lines'() {

        when:
        Dummy.log.debug1("Hello world")
        Dummy.log.debug1("Hello world")
        Dummy.log.debug1("Hello world")
        Dummy.log.debug1("Hello world")

        then:
        capture.toString().readLines().findAll { it.endsWith('Hello world') }.size() ==4
    }


    @Slf4j
    static class Dummy {
    }


}
