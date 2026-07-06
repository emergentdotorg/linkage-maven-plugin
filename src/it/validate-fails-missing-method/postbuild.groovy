String log = new File(basedir, 'build.log').text

assert log.contains('Missing method org.apache.commons.io.IOUtils.consume(java.io.Reader)')
assert log.contains('Missing class org.apache.commons.io.function.Uncheck')
assert log.contains('validate-fails-lib')
assert log.contains('BUILD FAILURE')
