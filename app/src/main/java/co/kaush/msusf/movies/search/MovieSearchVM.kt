package co.kaush.msusf.movies.search

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import co.kaush.msusf.MSApp
import co.kaush.msusf.movies.MovieRepository
import co.kaush.msusf.movies.MovieSearchResult
import co.kaush.msusf.movies.search.MSMovieEvent.*
import co.kaush.msusf.movies.search.MSMovieResult.*
import co.kaush.msusf.movies.search.MSMovieViewEffect.AddedToHistoryToastEffect
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import timber.log.Timber

/**
 * For this example, a simple ViewModel would have sufficed,
 * but in most real world examples we would use an AndroidViewModel
 *
 * Our Unit tests should still be able to run given this
 */
class MovieSearchVM(
    app: MSApp,
    private val movieRepo: MovieRepository
) : AndroidViewModel(app) {

    private val viewEventSubject: PublishSubject<MSMovieEvent> = PublishSubject.create()

    private lateinit var disposable: Disposable

    val viewState: Observable<ViewState>
    val viewEffects: Observable<MSMovieViewEffect>

    init {
            viewEventSubject
            .doOnNext { Timber.d("----- event $it") }
            .eventToResult()
            .doOnNext { Timber.d("----- result $it") }
            .share()
            .also { result ->
                viewState = result
                    .resultToViewState()
                    .doOnNext { Timber.d("----- vs $it") }
                    .replay(1)
                    .autoConnect(1) { disposable = it }

                viewEffects = result
                    .resultToViewEffect()
                    .doOnNext { Timber.d("----- ve $it") }
            }
    }

    override fun onCleared() {
        super.onCleared()
        disposable.dispose()
    }

    fun processInput(event: MSMovieEvent) {
        viewEventSubject.onNext(event)
    }

    // -----------------------------------------------------------------------------------
    // Internal helpers

    private fun Observable<MSMovieEvent>.eventToResult(): Observable<Lce<out MSMovieResult>> {
        return publish { o ->
            Observable.merge(
                o.ofType(ViewResumeEvent::class.java).onScreenLoad(),
                o.ofType(SearchMovieEvent::class.java).onSearchMovie(),
                o.ofType(AddToHistoryEvent::class.java).onAddToHistory(),
                o.ofType(RestoreFromHistoryEvent::class.java).onRestoreFromHistory()
            )
        }
    }

    private fun Observable<Lce<out MSMovieResult>>.resultToViewState(): Observable<ViewState> {
        return scan(ViewState()) { vs, result ->
            when (result) {
                is Lce.Content -> {
                    when (result.packet) {
                        is ScreenLoadResult -> {
                            vs.copy(searchBoxText = "")
                        }
                        is SearchMovieResult -> {
                            val movie: MovieSearchResult = result.packet.movie
                            vs.copy(
                                movieTitle = movie.title,
                                rating1 = movie.ratingSummary,
                                moviePosterUrl = movie.posterUrl,
                                searchedMovieReference = movie
                            )
                        }

                        is AddToHistoryResult -> {
                            val movieToBeAdded: MovieSearchResult = result.packet.movie

                            if (!vs.adapterList.contains(movieToBeAdded)) {
                                vs.copy(adapterList = vs.adapterList.plus(movieToBeAdded))
                            } else vs.copy()
                        }
                    }
                }

                is Lce.Loading -> {
                    vs.copy(
                        searchBoxText = null,
                        movieTitle = "Searching Movie...",
                        rating1 = "",
                        moviePosterUrl = "",
                        searchedMovieReference = null
                    )
                }

                is Lce.Error -> {
                    when (result.packet) {
                        is SearchMovieResult -> {
                            val movie: MovieSearchResult = result.packet.movie
                            vs.copy(movieTitle = movie.errorMessage!!)
                        }
                        else -> throw RuntimeException("Unexpected result LCE state")
                    }
                }
            }
        }
            .distinctUntilChanged()
    }

    private fun Observable<Lce<out MSMovieResult>>.resultToViewEffect(): Observable<MSMovieViewEffect> {
        return filter { it is Lce.Content && it.packet is AddToHistoryResult }
            .map<MSMovieViewEffect> { AddedToHistoryToastEffect }
    }

    // -----------------------------------------------------------------------------------
    // use cases

    private fun Observable<ViewResumeEvent>.onScreenLoad(): Observable<Lce<ScreenLoadResult>> {
        return map { Lce.Content(ScreenLoadResult) }
    }

    private fun Observable<SearchMovieEvent>.onSearchMovie(): Observable<Lce<SearchMovieResult>> {
        return switchMap { searchMovieEvent ->
            movieRepo.movieOnce(searchMovieEvent.searchedMovieTitle)
                .subscribeOn(Schedulers.io())
                .map {
                    if (it.errorMessage?.isNullOrBlank() == false) {
                        Lce.Error(SearchMovieResult(it))
                    } else {
                        Lce.Content(SearchMovieResult(it))
                    }
                }
                .onErrorReturn {
                    Lce.Error(SearchMovieResult(MovieSearchResult(result = false, errorMessage = it.localizedMessage)))
                }
                .startWith(Lce.Loading())
        }
    }

    private fun Observable<AddToHistoryEvent>.onAddToHistory(): Observable<Lce<AddToHistoryResult>> {
        return map { Lce.Content(AddToHistoryResult(it.searchedMovie)) }
    }

    private fun Observable<RestoreFromHistoryEvent>.onRestoreFromHistory(): Observable<Lce<SearchMovieResult>> {
        return map { Lce.Content(SearchMovieResult(it.movieFromHistory)) }
    }

// -----------------------------------------------------------------------------------
// LCE

    sealed class Lce<T> {
        class Loading<T> : Lce<T>()
        data class Content<T>(val packet: T) : Lce<T>()
        data class Error<T>(val packet: T) : Lce<T>()
    }

// -----------------------------------------------------------------------------------

    class MSMainVmFactory(
        private val app: MSApp,
        private val movieRepo: MovieRepository
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return MovieSearchVM(app, movieRepo) as T
        }
    }


    data class ViewState(
        val searchBoxText: String? = null,
        val movieTitle: String = "",
        val moviePosterUrl: String?,
        val genres: String = "",
        val plot: String = "",
        val rating1: String = "",

        val searchedMovieReference: MovieSearchResult? = null,
        val searchHistoryList: List<MovieSearchResult> = emptyList()
    )


}

sealed class MSMovieViewEffect {
    object AddedToHistoryToastEffect: MSMovieViewEffect()
}

sealed class MSMovieEvent {
    object ViewResumeEvent : MSMovieEvent()
    data class SearchMovieEvent(val searchedMovieTitle: String = "") : MSMovieEvent()
    data class AddToHistoryEvent(val searchedMovie: MovieSearchResult) : MSMovieEvent()
    data class RestoreFromHistoryEvent(val movieFromHistory: MovieSearchResult) : MSMovieEvent()
}

sealed class MSMovieResult {
    object ScreenLoadResult : MSMovieResult()
    data class SearchMovieResult(val movie: MovieSearchResult) : MSMovieResult()
    data class AddToHistoryResult(val movie: MovieSearchResult) : MSMovieResult()
}