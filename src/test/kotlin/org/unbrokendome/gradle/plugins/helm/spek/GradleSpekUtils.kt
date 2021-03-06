package org.unbrokendome.gradle.plugins.helm.spek

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder
import org.spekframework.spek2.dsl.LifecycleAware
import org.spekframework.spek2.lifecycle.MemoizedValue
import org.unbrokendome.gradle.plugins.helm.testutil.exec.GradleExecMock
import org.unbrokendome.gradle.plugins.helm.testutil.exec.ProcessOperationsGradleExecMock
import org.unbrokendome.gradle.plugins.helm.testutil.exec.install
import org.unbrokendome.gradle.plugins.helm.testutil.exec.withStatefulVerification
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


interface MemoizedGradleProject : MemoizedValue<Project> {

    var projectName: String

    fun initializer(initializer: Project.() -> Unit)

    fun applyPlugin(pluginType: KClass<out Plugin<Project>>) =
        initializer {
            plugins.apply(pluginType.java)
        }
}


inline fun <reified T : Plugin<Project>> MemoizedGradleProject.applyPlugin() =
    applyPlugin(T::class)


private data class DefaultMemoizedGradleProject(
    private val lifecycleAware: LifecycleAware
) : MemoizedGradleProject {

    override var projectName: String = ""

    private val initializers = mutableListOf<Project.() -> Unit>()


    override fun initializer(initializer: Project.() -> Unit) {
        initializers.add(initializer)
    }


    override fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, Project> {
        val memoized = lifecycleAware.memoized(
            factory = {
                ProjectBuilder.builder().run {
                    projectName.takeUnless { it.isEmpty() }?.let { withName(it) }
                    build()
                }.also { project ->
                    initializers.forEach { initializer ->
                        initializer(project)
                    }
                }
            },
            destructor = { project ->
                project.projectDir.deleteRecursively()
            }
        )
        return memoized.provideDelegate(thisRef, property)
    }
}


fun LifecycleAware.gradleProject(): MemoizedGradleProject =
    DefaultMemoizedGradleProject(this)


fun LifecycleAware.setupGradleProject(block: MemoizedGradleProject.() -> Unit): MemoizedValue<Project> {
    @Suppress("UNUSED_VARIABLE")
    val project: Project by gradleProject().also(block)

    return memoized()
}


fun <T : Task> LifecycleAware.gradleTask(
    taskType: KClass<T>,
    name: String? = null,
    config: T.() -> Unit = {}
): MemoizedValue<T> {
    val project: Project by memoized()
    val actualName = name ?: taskType.simpleName?.decapitalize() ?: "task"
    return memoized<T> {
        project.tasks.create(actualName, taskType.java, Action(config))
    }
}


inline fun <reified T : Task> LifecycleAware.gradleTask(name: String? = null, noinline config: T.() -> Unit = {}) =
    gradleTask(T::class, name, config)


fun LifecycleAware.gradleExecMock(): MemoizedValue<GradleExecMock> {
    val project: Project by memoized()

    val processOperationsExecMock by memoized { ProcessOperationsGradleExecMock.create(project) }

    beforeEachTest {
        processOperationsExecMock.install(project)
    }

    @Suppress("UNUSED_VARIABLE")
    val execMock by memoized { processOperationsExecMock.withStatefulVerification() }

    return memoized()
}
