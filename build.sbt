name := "goed-verhaal"

version := "0.1"

scalaVersion := "2.12.6"

val catsVersion  = "1.2.0"

libraryDependencies ++= Seq(
  "org.typelevel"               %% "cats-effect"                % "1.0.0-RC2",
  "org.typelevel"               %% "cats-laws"                  % catsVersion % Test,
  "org.typelevel"               %% "cats-kernel-laws"           % catsVersion % Test,
  "com.ironcorelabs"            %% "cats-scalatest"             % "2.2.0"     % Test
)

scalacOptions += "-Ypartial-unification"


addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.7")

