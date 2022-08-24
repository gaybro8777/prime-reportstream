import { NetworkErrorBoundary, useResource } from "rest-hooks";
import { Suspense } from "react";

import HipaaNotice from "../../components/HipaaNotice";
import Spinner from "../../components/Spinner";
import { ErrorPage } from "../error/ErrorPage";
import ReportResource from "../../resources/ReportResource";

import Summary from "./Summary";
import ReportDetails from "./ReportDetails";
import FacilitiesTable from "./FacilitiesTable";

/** Converts URL queries to a map-like object
 * @remarks Maybe this should make a real Map, not a map-like object? */
function useQuery(): { readonly [key: string]: string } {
    const query = window.location.search.slice(1);
    const queryMap = {};
    Object.assign(
        queryMap,
        ...query
            .split(",")
            .map((s) => s.split("="))
            .map(([k, v]) => ({ [k]: v }))
    );
    return queryMap;
}

/** @todo Refactor as part of {@link https://github.com/CDCgov/prime-reportstream/issues/4790 #4790} */
const DetailsContent = () => {
    const queryMap = useQuery();
    const reportId = queryMap?.["reportId"] || "";
    const report = useResource(ReportResource.detail(), { reportId: reportId });
    return (
        <>
            <Summary report={report} />
            <ReportDetails report={report} />
            <NetworkErrorBoundary
                fallbackComponent={() => <ErrorPage type="message" />}
            >
                <Suspense fallback={<Spinner />}>
                    <FacilitiesTable reportId={reportId} />
                </Suspense>
            </NetworkErrorBoundary>
            <HipaaNotice />
        </>
    );
};

/** @todo Refactor as part of {@link https://github.com/CDCgov/prime-reportstream/issues/4790 #4790} */
export const Details = () => {
    return (
        <NetworkErrorBoundary
            fallbackComponent={() => <ErrorPage type="page" />}
        >
            <Suspense fallback={<Spinner size="fullpage" />}>
                <DetailsContent />
            </Suspense>
        </NetworkErrorBoundary>
    );
};
