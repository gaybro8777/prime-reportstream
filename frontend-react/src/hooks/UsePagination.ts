import chunk from "lodash.chunk";
import range from "lodash.range";
import { useCallback, useEffect, useReducer } from "react";
import useDeepCompareEffect from "use-deep-compare-effect";

import {
    PaginationProps,
    SlotItem,
    OVERFLOW_INDICATOR,
} from "../components/Table/Pagination";

// A function that will return a cursor value for a resource in the paginated
// set.
export type CursorExtractor<T> = (arg: T) => string;

// A function that will resolve to a list of results give a start cursor and
// number of results.
export type ResultsFetcher<T> = (
    startCursor: string,
    numResults: number
) => Promise<T[]>;

// Returns a list of slots based on the USWDS pagination behavior rules.
// See https://designsystem.digital.gov/components/pagination/
export function getSlots(
    currentPageNum: number,
    finalPageNum?: number
): SlotItem[] {
    // For unbounded sets show the first, previous, current, and next pages. Put
    // the current page in Slot 4 and fill in gaps with additional pages or
    // overflow indicators.
    if (!finalPageNum) {
        // The current page can't go in Slot 4 for the first few pages, so just
        // fill in the numbers.
        if (currentPageNum <= 4) {
            return [1, 2, 3, 4, 5, 6, OVERFLOW_INDICATOR];
        }
        return [
            1,
            OVERFLOW_INDICATOR,
            currentPageNum - 1,
            currentPageNum,
            currentPageNum + 1,
            currentPageNum + 2,
            OVERFLOW_INDICATOR,
        ];
    }

    // For bounded sets, if there are no more than seven pages in the set, show
    // only that number of slots.
    if (finalPageNum <= 7) {
        return range(1, finalPageNum + 1);
    }

    // For the first four pages, show an overflow indicator before the final
    // page and then fill in the rest.
    if (currentPageNum <= 4) {
        return [1, 2, 3, 4, 5, OVERFLOW_INDICATOR, finalPageNum];
    }

    // There are five slots after the first page and overflow indicator. If the
    // current page and final page are within that range of five numbers, start
    // with the final page and backfill the previous slots.
    if (finalPageNum < currentPageNum + 4) {
        return [
            1,
            OVERFLOW_INDICATOR,
            ...range(finalPageNum - 4, finalPageNum + 1),
        ];
    }

    // Otherwise, we can safely put an overflow indicator between the next and
    // final pages.
    return [
        1,
        OVERFLOW_INDICATOR,
        currentPageNum - 1,
        currentPageNum,
        currentPageNum + 1,
        OVERFLOW_INDICATOR,
        finalPageNum,
    ];
}

interface RequestConfig {
    // Number of results to fetch when more results are needed to display the
    // pagination or page of results.
    numResults: number;
    // Start cursor for fetching more results.
    startCursor: string;
    // Page number corresponding to the start cursor in a request for more
    // results.
    startPageNum: number;
    // Page number selection that initiated the request.
    selectedPageNum: number;
}

// Internal state for the hook.
export interface PaginationState<T> {
    // Current page of results being displayed in the UI.
    currentPageNum: number;
    // Function for extracting the cursor value from a result in the paginated
    // set.
    extractCursor: CursorExtractor<T>;
    // The final page number of the paginated set or undefined if the end of the
    // set has not been reached.
    finalPageNum?: number;
    // Map of page numbers to the cursor of the first item on the page.
    pageCursorMap: Record<number, string>;
    // Map of page numbers to the list of results on each page.
    pageResultsMap: Record<number, T[]>;
    // Number of items on a page of results in the UI.
    pageSize: number;
    // Whether there is a in-flight request for more results.
    isLoading: boolean;
    // Optional set of parameters for requesting a new batch of results.
    requestConfig?: RequestConfig;
}

enum PaginationActionType {
    PROCESS_RESULTS = "PROCESS_RESULTS",
    RESET = "RESET",
    SET_SELECTED_PAGE = "SET_SELECTED_PAGE",
    SET_IS_LOADING = "SET_IS_LOADING",
}

export interface ProcessResultsPayload<T> {
    results: T[];
    requestConfig: RequestConfig;
}
type ResetPayload<T> = InitialStateArgs<T>;
type SetSelectedPagePayload = number;

interface PaginationAction<T> {
    type: PaginationActionType;
    payload:
        | ProcessResultsPayload<T>
        | ResetPayload<T>
        | SetSelectedPagePayload;
}

type PaginationReducer<PaginationState, PaginationAction> = (
    state: PaginationState,
    action: PaginationAction
) => PaginationState;

// Updates the state with results, cursors, and possible final page in response
// to a set of results returned from a fetch call.
export function processResultsReducer<T>(
    state: PaginationState<T>,
    { results, requestConfig }: ProcessResultsPayload<T>
): PaginationState<T> {
    const { numResults, startPageNum, selectedPageNum } = requestConfig;
    const { extractCursor, pageSize, pageCursorMap, pageResultsMap } = state;

    // Determine the number of whole pages we requested data for. Ignoring the
    // remainder accounts for a dangling result, which we use as an indicator of
    // a subsequent page.
    const numTargetWholePages = Math.floor(numResults / pageSize);
    let finalPageNum;

    const resultPages = chunk(results, pageSize);
    resultPages.forEach((resultsPage, i) => {
        const pageNum = startPageNum + i;
        // Store cursors for all known pages.
        // Don't store the cursor for Page 1, which retains the argument value
        // passed to the hook.
        if (pageNum > 1) {
            pageCursorMap[pageNum] = extractCursor(resultsPage[0]);
        }
        const isVisiblePage = i < numTargetWholePages;
        if (isVisiblePage) {
            // Store results only for visible pages.
            pageResultsMap[pageNum] = resultsPage;

            // This page number is the final page if:
            // a) is a visible page and it has fewer than pageSize results or
            // b) it is the last chunk in the returned results.
            const isIncompletePage = resultsPage.length < pageSize;
            const isLastChunk = i === resultPages.length - 1;
            if (isIncompletePage || isLastChunk) {
                finalPageNum = pageNum;
            }
        }
    });
    return {
        ...state,
        isLoading: false,
        currentPageNum: selectedPageNum,
        finalPageNum,
        pageResultsMap,
        pageCursorMap,
    };
}

// Handles the user's selection of a page. If no new results are needed to
// render the slots, this reducer immediately updates the current page with the
// selected page value. If more results are needed, this reducer does not update
// the current page, and instead creates a request config to trigger the
// fetching of a new batch of results.
export function setSelectedPageReducer<T>(
    state: PaginationState<T>,
    selectedPageNum: number
): PaginationState<T> {
    const slots = getSlots(selectedPageNum, state.finalPageNum);

    // The last page to fetch is the last page number in the slots, excluding
    // an overflow indicator in the last slot.
    const slotNumbers = slots.filter((s) => Number.isInteger(s)) as number[];
    const endPage = slotNumbers.pop();
    if (!endPage) {
        return {
            ...state,
            currentPageNum: selectedPageNum,
            requestConfig: undefined,
        };
    }

    const fetchedPageNumbers = Object.keys(state.pageResultsMap).map((k) =>
        parseInt(k)
    );
    const lastFetchedPage =
        fetchedPageNumbers.length > 0 ? Math.max(...fetchedPageNumbers) : 0;

    // We already have all the data that we need.
    if (lastFetchedPage === endPage) {
        return {
            ...state,
            currentPageNum: selectedPageNum,
            requestConfig: undefined,
        };
    }

    const numTargetWholePages = endPage - lastFetchedPage;
    // Add one more result than the total needed for the whole pages as an
    // indicator for whether there is a subsequent page.
    const numResults = numTargetWholePages * state.pageSize + 1;
    const startPageNum = lastFetchedPage + 1;
    const startCursor = state.pageCursorMap[startPageNum];

    return {
        ...state,
        isLoading: true,
        requestConfig: {
            numResults,
            startCursor,
            startPageNum,
            selectedPageNum,
        },
    };
}

function reducer<T>(
    state: PaginationState<T>,
    action: PaginationAction<T>
): PaginationState<T> {
    const { type, payload } = action;
    switch (type) {
        case PaginationActionType.PROCESS_RESULTS:
            return processResultsReducer(
                state,
                payload as ProcessResultsPayload<T>
            );
        case PaginationActionType.RESET:
            const initialState = getInitialState(payload as ResetPayload<T>);
            return setSelectedPageReducer(initialState, 1);
        case PaginationActionType.SET_SELECTED_PAGE:
            return setSelectedPageReducer(
                state,
                payload as SetSelectedPagePayload
            );
        default:
            return state;
    }
}

// Input parameters to the hook.
export interface UsePaginationProps<T> {
    startCursor: string;
    pageSize: number;
    fetchResults: ResultsFetcher<T>;
    extractCursor: CursorExtractor<T>;
}

// Output state object from the hook.
interface UsePaginationState<T> {
    currentPageResults: T[];
    isLoading: boolean;
    paginationProps?: PaginationProps;
}

// Arguments need to initialize the hook's internal state.
interface InitialStateArgs<T> {
    startCursor: string;
    pageSize: number;
    extractCursor: CursorExtractor<T>;
}

// Creates an initial internal state for the hook.
function getInitialState<T>({
    startCursor,
    pageSize,
    extractCursor,
}: InitialStateArgs<T>): PaginationState<T> {
    const currentPageNum = 1;
    return {
        currentPageNum,
        extractCursor,
        isLoading: false,
        pageCursorMap: {
            1: startCursor,
        },
        pageResultsMap: {},
        pageSize,
    };
}

// Hook for paginating through a set of results by means of a cursor values
// extracted from the items in the set.
function usePagination<T>({
    startCursor,
    pageSize,
    fetchResults,
    extractCursor,
}: UsePaginationProps<T>): UsePaginationState<T> {
    const [state, dispatch] = useReducer<
        PaginationReducer<PaginationState<T>, PaginationAction<T>>
    >(
        reducer,
        getInitialState({
            startCursor,
            pageSize,
            extractCursor,
        })
    );

    // Reset the state if any of the hook props change.
    useEffect(() => {
        dispatch({
            type: PaginationActionType.RESET,
            payload: {
                startCursor,
                pageSize,
                extractCursor,
            },
        });
    }, [fetchResults, pageSize, startCursor, extractCursor]);

    // Fetch a new batch of results when the fetch parameters change.
    const { requestConfig } = state;
    // The request config is an object, so to use it as an effect hook
    // dependency we need to use deep comparison, not reference equality.
    useDeepCompareEffect(() => {
        // Effect callbacks are synchronous so we have to declare the async
        // function inside it.
        async function doEffect() {
            if (!requestConfig) {
                return;
            }
            const results = await fetchResults(
                requestConfig.startCursor,
                requestConfig.numResults
            );
            dispatch({
                type: PaginationActionType.PROCESS_RESULTS,
                payload: {
                    results,
                    requestConfig,
                },
            });
        }
        doEffect();
    }, [fetchResults, requestConfig]);

    // Create a callback for changing the current page to pass down to the
    // pagination UI component.
    const setSelectedPage = useCallback(
        (pageNum: number) => {
            dispatch({
                type: PaginationActionType.SET_SELECTED_PAGE,
                payload: pageNum,
            });
        },
        [dispatch]
    );

    // Assemble props for the pagination UI component.
    const currentPageResults = state.pageResultsMap[state.currentPageNum] || [];
    let paginationProps: PaginationProps | undefined;
    if (currentPageResults.length > 0) {
        paginationProps = {
            slots: getSlots(state.currentPageNum, state.finalPageNum),
            setSelectedPage,
            currentPageNum: state.currentPageNum,
        };
    }

    return {
        currentPageResults,
        isLoading: state.isLoading,
        paginationProps,
    };
}

export default usePagination;
