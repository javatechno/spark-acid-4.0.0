#!/bin/bash

eg='\033[0;32m'
enc='\033[0m'
echoe () {
	OIFS=${IFS}
	IFS='%'
	echo -e $@
	IFS=${OIFS}
}

gprn() {
	echoe "${eg} >> ${1}${enc}"
}


## Setup ENV variables

export JAVA_HOME="/usr/lib/jvm/java-openjdk"

export HDFS_NAMENODE_USER="root"
export HDFS_SECONDARYNAMENODE_USER="root"
export HDFS_DATANODE_USER="root"
export YARN_RESOURCEMANAGER_USER="root"
export YARN_NODEMANAGER_USER="root"

export HADOOP_HOME="/hadoop"
export HADOOP_ROOT_LOGGER=DEBUG
export HADOOP_COMMON_LIB_NATIVE_DIR="/hadoop/lib/native"

## Add it to bashrc for starting hadoop
echo 'export JAVA_HOME="/usr/lib/jvm/java-openjdk"' >> ~/.bashrc
echo 'export HADOOP_HOME="/hadoop"' >> ~/.bashrc


rm /hadoop
ln -sf /hadoop-3.1.1 /hadoop

cp /conf/core-site.xml /hadoop/etc/hadoop
cp /conf/hdfs-site.xml /hadoop/etc/hadoop
cp /conf/hadoop-env.sh /hadoop/etc/hadoop
cp /conf/mapred-site.xml /hadoop/etc/hadoop
cp /conf/yarn-site.xml /hadoop/etc/hadoop
cp /conf/hive-site.xml /hive/conf/


#gprn "set up mysql"
#service mysqld start

# Set root password 
#mysql -uroot -e "set password = PASSWORD('root');"
#mysql -uroot -e "grant all privileges on *.* to 'root'@'%' identified by 'root';"
service sshd start

gprn "start yarn"
hadoop/sbin/start-yarn.sh &
sleep 5

gprn "Formatting name node"
hadoop/bin/hdfs namenode -format

gprn "Start hdfs"
hadoop/sbin/hadoop-daemon.sh start namenode
hadoop/sbin/start-dfs.sh
hadoop/sbin/hadoop-daemon.sh start datanode

export JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk-1.8.0.312.b07-2.el8_5.x86_64/jre
/hadoop/sbin/yarn-daemon.sh start nodemanager

jps

mkdir -p /hive/warehouse


gprn "Set up metastore DB"
hive/bin/schematool -dbType derby -initSchema

gprn "Start HMS server"
hive/bin/hive --service metastore -p  10000 &

gprn "Sleep and wait for HMS to be up and running"
sleep 20

gprn "Start HiveServer2"
hive/bin/hive --service hiveserver2 --hiveconf hive.server2.thrift.port=10001 --hiveconf hive.execution.engine=mr
