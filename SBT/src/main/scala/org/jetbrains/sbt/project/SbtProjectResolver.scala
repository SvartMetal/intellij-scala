package org.jetbrains.sbt
package project

import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.externalSystem.model.task.{ExternalSystemTaskNotificationListener, ExternalSystemTaskNotificationEvent, ExternalSystemTaskId}
import com.intellij.openapi.externalSystem.model.project._
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.externalSystem.model.{ExternalSystemException, DataNode}
import com.intellij.openapi.roots.DependencyScope
import java.io.File
import module.SbtModuleType
import settings._
import structure._
import data._

/**
 * @author Pavel Fatin
 */
class SbtProjectResolver extends ExternalSystemProjectResolver[SbtExecutionSettings] {
  def resolveProjectInfo(id: ExternalSystemTaskId, projectPath: String, isPreview: Boolean, settings: SbtExecutionSettings, listener: ExternalSystemTaskNotificationListener): DataNode[ProjectData] = {
    val path = {
      val file = new File(projectPath)
      if (file.isDirectory) file.getPath else file.getParent
    }

    val runner = new SbtRunner(settings.vmOptions, settings.customLauncher)

    val xml = runner.read(new File(path), !isPreview) { message =>
      listener.onStatusChange(new ExternalSystemTaskNotificationEvent(id, message.trim))
    } match {
      case Left(errors) => throw new ExternalSystemException(errors)
      case Right(node) => node
    }

    val data = StructureParser.parse(xml, new File(System.getProperty("user.home")))

    convert(data).toDataNode
  }

  private def convert(data: Structure): Node[ProjectData] = {
    val projects = data.projects

    val project = data.projects.headOption.getOrElse(throw new RuntimeException("No root project found"))

    val projectNode = createProject(project)

    val javaHome = project.java.flatMap(_.home).getOrElse(new File(System.getProperty("java.home")))
    val javacOptions = project.java.map(_.options).getOrElse(Seq.empty)

    projectNode.add(new ScalaProjectNode(javaHome, javacOptions))

    val libraries = {
      val modules = data.repository.map(_.modules).getOrElse {
        val ids = projects.flatMap(_.configurations.flatMap(_.modules)).distinct
        ids.map(id => Module(id, Seq.empty, Seq.empty, Seq.empty))
      }

      val scalas = projects.flatMap(_.scala).distinctBy(_.version)

      modules.map(createLibrary) ++ scalas.map(createCompilerLibrary)
    }

    projectNode.addAll(libraries)

    val moduleNodes: Seq[ModuleNode] = projects.map { project =>
      val moduleNode = createModule(project)
      moduleNode.add(createContentRoot(project))
      moduleNode.addAll(createLibraryDependencies(project)(moduleNode, libraries.map(t => t.data)))
      moduleNode.addAll(project.scala.map(createFacet(project, _)).toSeq)
      moduleNode.addAll(createUnmanagedDependencies(project)(moduleNode))
      moduleNode
    }

    projectNode.addAll(moduleNodes)

    val idToModuleNode = projects.zip(moduleNodes).map(p => (p._1.id, p._2)).toMap

    projects.zip(moduleNodes).foreach { case (moduleProject, moduleNode) =>
      moduleProject.dependencies.foreach { dependencyId =>
        val dependency = idToModuleNode.get(dependencyId).getOrElse(
          throw new ExternalSystemException("Cannot find project dependency: " + dependencyId))
        moduleNode.add(new ModuleDependencyNode(moduleNode, dependency))
      }
    }

    projectNode.addAll(projects.map(createBuildModule))

    projectNode
  }

  private def createFacet(project: Project, scala: Scala): ScalaFacetNode = {
    val basePackage = Some(project.organization).filter(_.contains(".")).mkString

    new ScalaFacetNode(scala.version, basePackage, nameFor(scala), scala.options)
  }

  private def createProject(project: Project): ProjectNode = {
    val result = new ProjectNode(project.base.path, project.base.path)
    result.setName(project.name)
    result
  }

  private def createLibrary(module: Module): LibraryNode = {
    val result = new LibraryNode(nameFor(module.id))
    result.addPaths(LibraryPathType.BINARY, module.binaries.map(_.path))
    result.addPaths(LibraryPathType.DOC, module.docs.map(_.path))
    result.addPaths(LibraryPathType.SOURCE, module.sources.map(_.path))
    result
  }

  private def nameFor(id: ModuleId) = s"${id.organization}:${id.name}:${id.revision}"

  private def createCompilerLibrary(scala: Scala): LibraryNode = {
    val result = new LibraryNode(nameFor(scala))
    val jars = scala.compilerJar +: scala.libraryJar +: scala.extraJars
    result.addPaths(LibraryPathType.BINARY, jars.map(_.path))
    result
  }

  private def nameFor(scala: Scala) = s"scala-compiler:${scala.version}"

  private def createModule(project: Project): ModuleNode = {
    val result = new ModuleNode(StdModuleTypes.JAVA.getId, project.id,
      project.base.path, project.base.path)

    result.setInheritProjectCompileOutputPath(false)

    project.configurations.find(_.id == "compile").foreach { configuration =>
      result.setCompileOutputPath(ExternalSystemSourceType.SOURCE, configuration.classes.path)
    }

    project.configurations.find(_.id == "test").foreach { configuration =>
      result.setCompileOutputPath(ExternalSystemSourceType.TEST, configuration.classes.path)
    }

    result
  }

  private def createContentRoot(project: Project): ContentRootNode = {
    val productinSources = relevantRootPathsIn(project, "compile")(_.sources)
    val productionResources = relevantRootPathsIn(project, "compile")(_.resources)
    val testSources = relevantRootPathsIn(project, "test")(_.sources)
    val testResources = relevantRootPathsIn(project, "test")(_.resources)

    val commonRoot = {
      val allRoots = productinSources ++ productionResources ++ testSources ++ testResources :+ project.base

      commonAncestorOf(allRoots).getOrElse(throw new ExternalSystemException(
        "Cannot determine common root in project: " +  project.name))
    }

    val result = new ContentRootNode(commonRoot.path)

    result.storePaths(ExternalSystemSourceType.SOURCE, productinSources.map(_.path))
    result.storePaths(ExternalSystemSourceType.RESOURCE, productionResources.map(_.path))

    result.storePaths(ExternalSystemSourceType.TEST, testSources.map(_.path))
    result.storePaths(ExternalSystemSourceType.TEST_RESOURCE, testResources.map(_.path))

    result
  }

  private def commonAncestorOf(files: Seq[File]): Option[File] = {
    files.map(pathTo) match {
      case Seq() => None
      case Seq(firstPath, subsequentPaths @ _*) =>
        val commonPath = subsequentPaths.foldLeft(firstPath) { (acc, path) =>
          acc.zip(path).takeWhile(p => p._1 == p._2).map(_._1)
        }
        commonPath.lastOption
    }
  }

  private def pathTo(file: File): Seq[File] =
    Option(file.getParentFile).map(pathTo).getOrElse(Seq.empty) :+ file

  private def createBuildModule(project: Project): ModuleNode = {
    val name = project.id + Sbt.BuildModuleSuffix
    val path = project.base.path + "/project"

    val result = new ModuleNode(SbtModuleType.instance.getId, name, path, path)

    result.setInheritProjectCompileOutputPath(false)
    result.setCompileOutputPath(ExternalSystemSourceType.SOURCE, path + "/target/idea-classes")
    result.setCompileOutputPath(ExternalSystemSourceType.TEST, path + "/target/idea-test-classes")

    result.add(createBuildContentRoot(project))
    result.add(createModuleLevelDependency(Sbt.BuildLibraryName,
      project.build.classpath.filter(_.exists).map(_.path), DependencyScope.COMPILE)(result))

    result
  }

  private def createBuildContentRoot(project: Project): ContentRootNode = {
    val root = project.base / "project"

    val result = new ContentRootNode(root.path)

    val sourceDirs = Seq(root) // , base << 1
    val exludedDirs = project.configurations
              .flatMap(it => it.sources ++ it.resources)
              .filter(isRelevant).map(_.file) :+ root / "target"

    result.storePaths(ExternalSystemSourceType.SOURCE, sourceDirs.map(_.path))
    result.storePaths(ExternalSystemSourceType.EXCLUDED, exludedDirs.map(_.path))

    result
  }

  private def relevantRootPathsIn(project: Project, scope: String)
                                 (selector: Configuration => Seq[Directory]): Seq[File] = {
    project.configurations.find(_.id == scope)
            .map(selector)
            .getOrElse(Seq.empty)
            .filter(isRelevant)
            .map(_.file)
  }

  private def isRelevant(directory: Directory): Boolean = directory.managed || directory.file.exists
  
  private def createLibraryDependencies(project: Project)(moduleData: ModuleData, libraries: Seq[LibraryData]): Seq[LibraryDependencyNode] = {
    val moduleToConfigurations =
      project.configurations
        .flatMap(configuration => configuration.modules.map(module => (module, configuration)))
        .groupBy(_._1)
        .mapValues(_.unzip._2.toSet)
        .toSeq

    moduleToConfigurations.map { case (module, configurations) =>
      val name = nameFor(module)
      val library = libraries.find(_.getExternalName == name).getOrElse(
        throw new ExternalSystemException("Library not found: " + name))
      val data = new LibraryDependencyNode(moduleData, library, LibraryLevel.PROJECT)
      data.setScope(scopeFor(configurations))
      data
    } ++ project.scala.toSeq.map { scala =>
      val name: String = nameFor(scala)
      val library = libraries.find(_.getExternalName == name).getOrElse(
        throw new ExternalSystemException("Library not found: " + name))
      val data = new LibraryDependencyNode(moduleData, library, LibraryLevel.PROJECT)
      data.setScope(DependencyScope.PROVIDED)
      data
    } //todo: this is the hack for removing unused libraries in external system
  }

  private def createUnmanagedDependencies(project: Project)(moduleData: ModuleData): Seq[LibraryDependencyNode] = {
    val jarsToConfigurations =
      project.configurations
        .filter(_.jars.nonEmpty)
        .map(configuration => (configuration.jars, configuration))
        .groupBy(_._1)
        .mapValues(_.unzip._2.toSet)
        .toSeq

    jarsToConfigurations.map { case (jars, configurations) =>
      createModuleLevelDependency(Sbt.UnmanagedLibraryName, jars.map(_.path), scopeFor(configurations))(moduleData)
    }
  }

  private def createModuleLevelDependency(name: String, binaries: Seq[String], scope: DependencyScope)
                                         (moduleData: ModuleData): LibraryDependencyNode = {

    val libraryNode = new LibraryNode(name)
    libraryNode.addPaths(LibraryPathType.BINARY, binaries)

    val result = new LibraryDependencyNode(moduleData, libraryNode, LibraryLevel.MODULE)
    result.setScope(scope)
    result
  }

  private def scopeFor(configurations: Set[Configuration]): DependencyScope = {
    val ids = configurations.map(_.id)

    if (ids.contains("compile"))
      DependencyScope.COMPILE
    else if (ids.contains("test"))
      DependencyScope.TEST
    else if (ids.contains("runtime"))
      DependencyScope.RUNTIME
    else
      DependencyScope.PROVIDED
  }

  def cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener) = false
}
