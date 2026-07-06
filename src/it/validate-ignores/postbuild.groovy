String log = new File(basedir, 'build.log').text

// the missing method is still reported, but only as a warning
assert log.contains('Missing method org.apache.commons.io.IOUtils.consume(java.io.Reader)')
// the org.apache.commons.io.function.* missing classes are suppressed by ignoredClassPatterns
assert !log.contains('Missing class org.apache.commons.io.function')
assert log.contains('BUILD SUCCESS')
