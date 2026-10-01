#include "myndhamr/alignment.hpp"
#include <iostream>
#include <iomanip>
#include <string>
#include <stdexcept>
// Private process protocol v1: stdin rows sourceXYZ targetXYZ; stdout s,row-majorR,t then residual/inlier rows.
int main(int argc,char** argv) {
    try {
        if(argc!=4) throw std::invalid_argument("Usage: myndhamr_align threshold_meters min_inlier_fraction minimum_metric_baseline");
        myndhamr::AlignmentOptions options; options.threshold_meters=std::stod(argv[1]);
        options.minimum_inlier_fraction=std::stod(argv[2]);options.minimum_metric_baseline=std::stod(argv[3]);
        std::vector<myndhamr::Correspondence> pairs;
        double x,y,z,a,b,c;
        while(std::cin>>x) {
            if(!(std::cin>>y>>z>>a>>b>>c)) throw std::invalid_argument("Malformed correspondence row");
            pairs.push_back({{x,y,z},{a,b,c}});
        }
        if(!std::cin.eof()) throw std::invalid_argument("Non-numeric correspondence data");
        const auto result=myndhamr::align_similarity(pairs,options);
        std::cout<<std::setprecision(17)<<result.scale;
        for(int row=0;row<3;++row) for(int col=0;col<3;++col) std::cout<<' '<<result.rotation_world_sfm(row,col);
        for(int row=0;row<3;++row) std::cout<<' '<<result.translation_world_sfm[row];
        std::cout<<' '<<result.source_condition<<'\n';
        for(std::size_t i=0;i<pairs.size();++i) std::cout<<result.residual_meters[i]<<' '<<result.inliers[i]<<'\n';
        return 0;
    } catch(const std::exception& error) { std::cerr<<error.what()<<'\n';return 2; }
}
