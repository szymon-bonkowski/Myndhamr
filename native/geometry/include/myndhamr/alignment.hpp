#pragma once
#include <Eigen/Core>
#include <vector>
namespace myndhamr {
// Column vectors; source is arbitrary SfM, target is capture-world metres.
struct Correspondence { Eigen::Vector3d source; Eigen::Vector3d target; };
struct Similarity {
    double scale;
    Eigen::Matrix3d rotation_world_sfm;
    Eigen::Vector3d translation_world_sfm;
    std::vector<double> residual_meters;
    std::vector<bool> inliers;
    double source_condition;
    Eigen::Vector3d apply(const Eigen::Vector3d& point) const;
};
struct AlignmentOptions {
    double threshold_meters = 0.05;
    double minimum_inlier_fraction = 0.6;
    double minimum_metric_baseline = 0.05;
    unsigned maximum_trials = 4096;
};
Similarity align_similarity(const std::vector<Correspondence>& pairs,
                            const AlignmentOptions& options = {});
}
