# 打包命令
mvn -B -Prelease-nacos,!dev clean install -Dmaven.test.skip=true -Drat.skip=true -Dspotbugs.skip=true -DtrimStackTrace=false -U -e

# 打包完后
- distribution/target/nacos-server-3.2.1-SNAPSHOT.tar.gz
- 将压缩包放到nacos-docker项目中
- 利用dockerfile,打成docker镜像