// (c) Copyright [2020-2021] Micro Focus or one of its affiliates.
// Licensed under the Apache License, Version 2.0 (the "License");
// You may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

scalaVersion := "2.12.12"
name := "spark-vertica-connector-kerberos-example"
organization := "com.vertica"
version := "3.0.1"

resolvers += "Artima Maven Repository" at "https://repo.artima.com/releases"
resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.4.1",
  "org.apache.spark" %% "spark-core" % "3.2.0",
  "org.apache.spark" %% "spark-sql" % "3.2.0",
  "com.vertica.spark" % "vertica-spark" % "3.0.1-slim"
)

assembly / assemblyShadeRules := {
  val shadePackage = "com.azavea.shaded.demo"
  Seq(
    ShadeRule.rename("cats.kernel.**" -> s"$shadePackage.cats.kernel.@1").inAll
  )
}

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

Runtime / unmanagedClasspath += new File("/etc/hadoop/conf/")
