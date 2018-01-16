/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */


val kamonCore  = "io.kamon" %% "kamon-core" % "0.6.7"

lazy val root = (project in file("."))
  .settings(name := "kamon-logback-reporter")
  .settings(
        libraryDependencies ++=
          compileScope(kamonCore, "ch.qos.logback"                %   "logback-classic"         % "1.1.7",
                       "net.logstash.logback" % "logstash-logback-encoder" % "4.8") ++
          testScope(scalatest, akkaDependency("testkit").value, slf4jApi))
