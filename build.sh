cd shaded-dependencies/
rm -rf target/
sbt clean publishLocal
cd ..
rm -rf target/
sbt clean assembly

