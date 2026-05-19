publish:
    op run --env-file .env.publish -- ./mill --no-server mill.javalib.SonatypeCentralPublishModule/publishAll
