package com.enjoyyourtime.globalfitness.training

import android.content.Context
import com.enjoyyourtime.globalfitness.training.data.CelebrationStore
import com.enjoyyourtime.globalfitness.training.data.ExercisePhotos
import com.enjoyyourtime.globalfitness.training.data.TrainingDatabase
import com.enjoyyourtime.globalfitness.training.data.TrainingRepository
import com.enjoyyourtime.globalfitness.training.data.TrainingSettings
import kotlinx.coroutines.CoroutineScope

/** Training feature: exercise catalog, the user's machines, workouts and their settings. */
class TrainingModule(context: Context, scope: CoroutineScope) {
    val settings = TrainingSettings(context)
    val database = TrainingDatabase(context)
    val repository = TrainingRepository(database, scope)
    val photos = ExercisePhotos(context)

    /** Achievement levels already celebrated on this phone. */
    val celebrations = CelebrationStore(context)
}
