# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM centos:latest

RUN cd /etc/yum.repos.d/
RUN sed -i 's/mirrorlist/#mirrorlist/g' /etc/yum.repos.d/CentOS-*
RUN sed -i 's|#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*

RUN yum -y update
RUN yum -y install epel-release


RUN yum -y install java-1.8.0-openjdk-devel java-1.8.0-openjdk
RUN ln -s /usr/lib/jvm//java-1.8.0-openjdk-amd64/ /usr/lib/jvm/java-1.8.0
RUN ln -s /usr/lib/jvm//java-1.7.0-openjdk-amd64/ /usr/lib/jvm/java-1.7.0

#RUN yum -y install vim
RUN yum -y install wget tar sudo rsync

RUN yum -y install initscripts httpd

RUN wget https://archive.apache.org/dist/hadoop/common/hadoop-3.1.1/hadoop-3.1.1.tar.gz
RUN tar -xvzf hadoop-3.1.1.tar.gz
RUN ln -sf /hadoop-3.1.1 /hadoop

RUN wget https://archive.apache.org/dist/hive/hive-3.1.3/apache-hive-3.1.3-bin.tar.gz
RUN tar -xvzf apache-hive-3.1.3-bin.tar.gz
RUN ln -sf /apache-hive-3.1.3-bin /hive

#RUN yum -y install \
#    mysql-server \
#     && yum -y clean all && rm -rf /tmp/* /var/tmp/*

#RUN wget https://dev.mysql.com/get/Downloads/Connector-J/mysql-connector-j-9.1.0.tar.gz
#RUN tar -xvzf mysql-connector-j-9.1.0.tar.gz
#RUN ln -sf /mysql-connector-j-9.1.0/mysql-connector-j-9.1.0.jar apache-hive-3.1.2-bin/lib/mysql-connector-java.jar

# Setup sock proxy
RUN yum install -y openssh openssh-clients openssh-server

# passwordless ssh
RUN ssh-keygen -q -N "" -t rsa -f /root/.ssh/id_rsa
RUN cp /root/.ssh/id_rsa.pub /root/.ssh/authorized_keys

RUN chmod 755 /root && chmod 700 /root/.ssh
#RUN passwd --unlock root

RUN yum install -y vim mlocate unzip

#RUN wget https://archive.apache.org/dist/hadoop/common/hadoop-2.7.0/hadoop-2.7.0.tar.gz
#RUN tar -xvzf hadoop-2.7.0.tar.gz


# Copy configuration files
RUN mkdir /conf
COPY core-site.xml /conf/core-site.xml
COPY hdfs-site.xml /conf/hdfs-site.xml
COPY hadoop-env.sh /conf/hadoop-env.sh
COPY yarn-site.xml /conf/yarn-site.xml

COPY mapred-site.xml /conf/mapred-site.xml
COPY hive-site.xml /conf/hive-site.xml
COPY bootstrap.sh /bootstrap.sh
RUN

# HDFS ports
EXPOSE 1004 1006 8020 9866 9867 9870 9864 50470 9000

# YARN ports
EXPOSE 8030 8031 8032 8033 8040 8041 8042 8088 10020 19888

# HIVE ports
EXPOSE 9083 10000

# SOCKS port
EXPOSE 1180

# mysql expose
EXPOSE 3306

# HDFS datnode
EXPOSE 9866

CMD ["/bin/bash", "-c", "/bootstrap.sh >/tmp/boostrap.log"]
