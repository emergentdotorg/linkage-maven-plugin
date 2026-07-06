String log = new File(basedir, 'build.log').text

assert log.contains('Linkage scanned')
assert log.contains('0 missing classes, 0 missing methods')
assert log.contains('BUILD SUCCESS')
